package appctr

import (
	"bufio"
	"context"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"github.com/creack/pty"
	"github.com/gliderlabs/ssh"
	"github.com/pkg/sftp"
	gossh "golang.org/x/crypto/ssh"
	_ "golang.org/x/mobile/bind"
)

// --- LOGGING SYSTEM ---

type LogManager struct {
	mu      sync.RWMutex
	logs    []string
	maxSize int
}

var logManager = &LogManager{
	logs:    make([]string, 0, 10000),
	maxSize: 10000,
}

func (lm *LogManager) AddLog(entry string) {
	lm.mu.Lock()
	defer lm.mu.Unlock()
	if len(lm.logs) >= lm.maxSize {
		lm.logs = lm.logs[len(lm.logs)/2:]
	}
	lm.logs = append(lm.logs, entry)
}

func (lm *LogManager) GetLogs() string {
	lm.mu.RLock()
	defer lm.mu.RUnlock()
	return strings.Join(lm.logs, "\n")
}

func (lm *LogManager) ClearLogs() {
	lm.mu.Lock()
	defer lm.mu.Unlock()
	lm.logs = make([]string, 0, lm.maxSize)
}

func GetLogs() string { return logManager.GetLogs() }
func ClearLogs()      { logManager.ClearLogs() }

type dualHandler struct {
	textHandler slog.Handler
}

func newDualHandler() *dualHandler {
	return &dualHandler{
		textHandler: slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{
			Level: slog.LevelDebug,
		}),
	}
}

func (h *dualHandler) Enabled(ctx context.Context, level slog.Level) bool { return true }

func (h *dualHandler) Handle(ctx context.Context, r slog.Record) error {
	timestamp := r.Time.Format("15:04:05")
	level := r.Level.String()

	var sb strings.Builder
	sb.WriteString(r.Message)
	r.Attrs(func(a slog.Attr) bool {
		sb.WriteString(" ")
		sb.WriteString(a.Key)
		sb.WriteString("=")
		sb.WriteString(fmt.Sprintf("%v", a.Value.Any()))
		return true
	})

	entry := fmt.Sprintf("%s [%s] %s", timestamp, level, sb.String())
	logManager.AddLog(entry)

	return h.textHandler.Handle(ctx, r)
}

func (h *dualHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &dualHandler{textHandler: h.textHandler.WithAttrs(attrs)}
}

func (h *dualHandler) WithGroup(name string) slog.Handler {
	return &dualHandler{textHandler: h.textHandler.WithGroup(name)}
}

func init() {
	slog.SetDefault(slog.New(newDualHandler()))
}

// --- MAIN LOGIC ---

func setWinsize(f *os.File, w, h int) {
	_, _, _ = syscall.Syscall(syscall.SYS_IOCTL, f.Fd(), uintptr(syscall.TIOCSWINSZ),
		uintptr(unsafe.Pointer(&struct{ h, w, x, y uint16 }{uint16(h), uint16(w), 0, 0})))
}

var cmd *exec.Cmd
var sshserver *ssh.Server
var PC pathControl

type Closer interface {
	Close() error
}

func IsRunning() bool { return cmd != nil && cmd.Process != nil }

type StartOptions struct {
	SSHServer    string
	ExecPath     string
	SocketPath   string
	StatePath    string
	Socks5Server string
	CloseCallBack Closer
	AuthKey      string
	ExtraUpArgs  string // НОВОЕ ПОЛЕ
}

func Start(opt *StartOptions) {
	if IsRunning() {
		return
	}

	if opt.Socks5Server == "" {
		opt.Socks5Server = ":1055"
	}

	PC = newPathControl(opt.ExecPath, opt.SocketPath, opt.StatePath)

	if opt.SSHServer != "" {
		go func() {
			if err := startSshServer(opt.SSHServer, PC); err != nil {
				slog.Error("ssh server", "err", err)
			}
		}()
	}

	go func() {
		err := tailscaledCmd(PC, opt.Socks5Server)
		if err != nil {
			slog.Error("tailscaled cmd", "err", err)
		}

		Stop()

		if opt.CloseCallBack != nil {
			opt.CloseCallBack.Close()
		}
	}()

	// Запускаем процесс регистрации/подключения в отдельной горутине
	go registerMachineWithAuthKey(PC, opt)
}

func registerMachineWithAuthKey(PC pathControl, opt *StartOptions) {
	count := 0
	// Пытаемся подключиться к сокету несколько раз (ждем пока демон запустится)
	for count <= 10 {
		_, err := os.Stat(PC.Socket())
		if err != nil {
			count++
			time.Sleep(time.Second)
			continue
		}

		// Формируем команду: tailscale up ...
		args := []string{"--socket", PC.Socket(), "up", "--timeout", "15s"}

		// Если есть AuthKey, добавляем
		if opt.AuthKey != "" {
			args = append(args, "--auth-key", opt.AuthKey)
		}

		// Если есть Дополнительные аргументы, парсим и добавляем
		if opt.ExtraUpArgs != "" {
			// strings.Fields разбивает строку по пробелам, игнорируя лишние пробелы
			customFlags := strings.Fields(opt.ExtraUpArgs)
			args = append(args, customFlags...)
		}

		slog.Info("Running tailscale up", "args", args)

		data, err := exec.Command(PC.Tailscale(), args...).CombinedOutput()
		slog.Info("tailscale up result", "output", string(data), "err", err)

		if err != nil {
			count++
			time.Sleep(time.Second * 2)
			continue
		}

		break
	}
}

func Stop() {
	if sshserver != nil {
		slog.Info("stop ssh server")
		sshserver.Close()
		sshserver = nil
	}

	x := cmd
	cmd = nil

	if x != nil && x.Process != nil {
		slog.Info("stop tailscaled cmd")
		_ = x.Process.Signal(syscall.SIGTERM)
		go func() {
			time.Sleep(time.Second * 5)
			if x.Process != nil {
				_ = x.Process.Kill()
			}
		}()
	}
}

func rm(path ...string) {
	if len(path) == 0 {
		return
	}
	args := []string{"-rf"}
	args = append(args, path...)
	data, err := exec.Command("/system/bin/rm", args...).CombinedOutput()
	slog.Info("rm", "cmd", args, "output", string(data), "err", err)
}

func ln(src, dst string) {
	cmd := exec.Command("/system/bin/ln", "-s", src, dst)
	data, err := cmd.CombinedOutput()
	slog.Info("ln", "cmd", cmd.String(), "output", string(data), "err", err)
}

type pathControl struct {
	execPath   string
	statePath  string
	socketPath string
	execDir    string
	dataDir    string
}

func newPathControl(execPath, socketPath, statePath string) pathControl {
	return pathControl{
		execPath:   execPath,
		statePath:  statePath,
		socketPath: socketPath,
		execDir:    filepath.Dir(execPath),
		dataDir:    filepath.Dir(socketPath),
	}
}

func (p pathControl) TailscaledSo() string { return p.execPath }
func (p pathControl) Tailscaled() string   { return filepath.Join(p.dataDir, "tailscaled") }
func (p pathControl) TailscaleSo() string  { return filepath.Join(p.execDir, "libtailscale.so") }
func (p pathControl) Tailscale() string    { return filepath.Join(p.dataDir, "tailscale") }
func (p pathControl) DataDir(s ...string) string {
	if len(s) == 0 {
		return p.dataDir
	}
	return filepath.Join(append([]string{p.dataDir}, s...)...)
}
func (p pathControl) Socket() string { return p.socketPath }
func (p *pathControl) State() string { return p.statePath }

func tailscaledCmd(p pathControl, socks5host string) error {
	rm(p.Tailscale(), p.Tailscaled())
	ln(p.TailscaledSo(), p.Tailscale())
	ln(p.TailscaledSo(), p.Tailscaled())

	cmd = exec.Command(
		p.Tailscaled(),
		"--tun=userspace-networking",
		"--socks5-server="+socks5host,
		"--outbound-http-proxy-listen=:1057",
		fmt.Sprintf("--statedir=%s", p.State()),
		fmt.Sprintf("--socket=%s", p.Socket()),
	)
	cmd.Dir = p.DataDir()
	cmd.Env = []string{
		fmt.Sprintf("TS_LOGS_DIR=%s/logs", p.DataDir()),
	}

	errChan := make(chan error)

	go func() {
		stdOut, err := cmd.StdoutPipe()
		if err != nil {
			errChan <- err
			return
		}
		s := bufio.NewScanner(stdOut)
		for s.Scan() {
			slog.Info(s.Text())
		}
	}()

	go func() {
		stdErr, err := cmd.StderrPipe()
		if err != nil {
			errChan <- err
			return
		}
		s := bufio.NewScanner(stdErr)
		for s.Scan() {
			slog.Info(s.Text())
		}
		errChan <- nil
	}()

	return cmd.Run()
}

// --- SSH PART ---

func startSshServer(addr string, pc pathControl) error {
	p, _ := pem.Decode([]byte(PrivateKey))
	key, _ := x509.ParsePKCS1PrivateKey(p.Bytes)
	signer, err := gossh.NewSignerFromKey(key)
	if err != nil {
		return err
	}

	ssh_server := ssh.Server{
		Addr:        addr,
		HostSigners: []ssh.Signer{signer},
		SubsystemHandlers: map[string]ssh.SubsystemHandler{
			"sftp": sftpHandler,
		},
		Handler: func(s ssh.Session) {
			ptyHandler(s, pc)
		},
	}

	sshserver = &ssh_server
	slog.Info("starting ssh server", "host", addr)
	return ssh_server.ListenAndServe()
}

var ptyWelcome = `
Welcome to Tailscaled SSH
	Tailscaled: %s
	Work Dir: %s
	RemoteAddr: %s
`

func ptyHandler(s ssh.Session, pc pathControl) {
	_, _ = fmt.Fprintf(s, ptyWelcome, pc.TailscaledSo(), pc.DataDir(), s.RemoteAddr())
	slog.Info("new pty session", "remote addr", s.RemoteAddr())

	cmd := exec.Command("/system/bin/sh")
	cmd.Dir = pc.DataDir()
	ptyReq, winCh, isPty := s.Pty()
	if isPty {
		cmd.Env = append(cmd.Env, fmt.Sprintf("TERM=%s", ptyReq.Term))
		f, err := pty.Start(cmd)
		if err != nil {
			slog.Error("start pty", "err", err)
			return
		}
		go func() {
			for win := range winCh {
				setWinsize(f, win.Width, win.Height)
			}
		}()
		go func() {
			_, _ = io.Copy(f, s)
			f.Close()
		}()
		_, _ = io.Copy(s, f)
		s.Close()
		_ = cmd.Wait()
		slog.Info("session exit", "remote addr", s.RemoteAddr())
	} else {
		_, _ = io.WriteString(s, "No PTY requested.\n")
		_ = s.Exit(1)
	}
}

func sftpHandler(sess ssh.Session) {
	slog.Info("new sftp session", "remote addr", sess.RemoteAddr())
	debugStream := io.Discard
	serverOptions := []sftp.ServerOption{sftp.WithDebug(debugStream)}
	server, err := sftp.NewServer(sess, serverOptions...)
	if err != nil {
		slog.Error("sftp server init", "err", err)
		return
	}
	if err := server.Serve(); err == io.EOF {
		server.Close()
		slog.Info("sftp client exited session.")
	} else if err != nil {
		slog.Error("sftp server completed", "err", err)
	}
	slog.Info("sftp session exited")
}

var PrivateKey = `-----BEGIN RSA PRIVATE KEY-----
MIIEpQIBAAKCAQEA0ludCFgG93sH//e/5CxAFG/PTjclfTDKaXpl2EBZQmoZSfJE
/4UCy/tUynYEovEbBT3PDyw7LmVnnyktetV4ra3OtBM0iNdZnZeIvW0kQaC7alGe
3sHzNfTxT60w3drrjLzOX+Wd9E/WZ4ScXzhgJ8kzqtRBbGgSukTSxgPgz89XiIDI
P+j721TrQYgOOnje4+SGEWL3TTAENDayk6RWom/58LstYz42TdaBFWHz+W2nG+Dw
EkzywnKGuMpccdNOvEnaoxVUZ+6OC7qOGfjSKBJIDmj39+XaVeduLMbjAy8cGAl4
n3HFTBMPupRqC6sbSrgpZ4MiNfrElF4cDXrBWQIDAQABAoIBAF6klVxhrpC+K/VA
VHemaRZIz+6S5S0UPJ2EUjofiYlWDxa0B9Mm1wFLjPSicKeW7t9G1dgvwFi5iwuT
DUFMtkT+BBgE5AgFS+6ZdQ41ArD8ThYhrubuQCywjbmZZHkMvBnQANIojw6StRZS
FcDJrol3/uUHJoBNus9Pk70/lXApOfgy+Yg3RTIPy+AMHr4exSGEGATFMFrOyiit
+xYBSnHFQzt63UsLUL5zWDFcljH5SmQJAkoCtrN3oiZRBb4v3TWYzSvDIR8BTuoD
Fj/EI9kWFyzx0MBpfOcU+ggNw1KSX+fsyRrMnirFg7HB7F7wL9MiJbihUVnbxDs8
Fy4vr2ECgYEA+tCmmr258UQcdxll0GgNz/WcY03HJlcZkfnnUpJKyb+K3Lpc5ekz
BrXKYNZ0A4gm8/8P55ykIPbWH2mi6/cDIkvsoGYOCac5P2Nf+W54wWtFzjuKx+x6
aoIKOCoQr69XM+KrbwZoku8g6VatRsJ1oMupZM3ucaTOBCm0hKVPAy0CgYEA1rTb
f/F7K8eJUbmX7dK3tdUuDxDMnf9Zp47JGZOJFuwZvHhZ7nOPw2HntSwVIk651pxc
kZfOWswPPHRxLztnEtBnwskM8e8RHMJHTeLalpHkdBoWT7KQq+uIU0KhiuRiqPcu
I16tZr0ciCgn9QmmtCJH+bpATMy5ZpDTfNHpQl0CgYEA4pzUeulC8F8WzPDwkb0C
BcwnMX3bmqOFoePGAk/VLLVYNJhZSQ1LIhvsL1Rz26EPeNMSPrTDglkjG5ypLEOw
3DL3J/Eta8FgMwqJc2dByZgvqOcZPAtIi6TUsOwoyWNGCcYaGKUUpPVTqh+7TTxz
ZQW+FisN7jX2QcKgrFxjqD0CgYEAgALAxB2T1FxZcRJ4lOEHizAZD/5yINl3+MDX
AZrHJ5WJGqee5t6bnmAnKAuqZhQOFPiQ8HVUISp9Awxh10lRgRQkaSw5vZ1N1Jm4
raVNsmw1i0tqdgX+36HEW+/kJM1aTWdiaNAwDos+EafvetdQPyIZS7lSUPfWqmI6
1bbJnjkCgYEA6M9HYlnaVPAHfguDugSeLJOia46Ui7aJh43znLlU/PoUdRRoBUmi
hUwJg5EHLSdbFj6vtwhqdnUwcH8v3HYK4vbUVamvCYF6kKCRmL2lyz9SH6yxHcPJ
zeMifjk2UYMZK8A0Ik7GxsHfseOx9QeWRbX8VR9QPuuwpGMVdQkeBgA=
-----END RSA PRIVATE KEY-----`