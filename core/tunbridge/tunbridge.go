// Package tunbridge 实现 sing-box 的 adapter.PlatformInterface：
// 把安卓 VpnService 交来的 tun fd 塞进 sing-tun 建设备（安卓应用不能自建 tun）。
package tunbridge

import (
	"context"
	"net/netip"

	tun "github.com/sagernet/sing-tun"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
)

// Bridge 保存当前 VpnService fd。
type Bridge struct {
	FD int
}

var _ adapter.PlatformInterface = (*Bridge)(nil)

func (b *Bridge) Initialize(networkManager adapter.NetworkManager) error { return nil }
func (b *Bridge) UsePlatformAutoDetectInterfaceControl() bool   { return false }
func (b *Bridge) AutoDetectInterfaceControl(fd int) error       { return nil }

// UsePlatformInterface=true => tun inbound 调 OpenInterface，不自己 open /dev/net/tun
func (b *Bridge) UsePlatformInterface() bool { return true }

func (b *Bridge) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	options.FileDescriptor = b.FD
	return tun.New(*options)
}

func (b *Bridge) ProcessPlatformOptions(options option.TunPlatformOptions) error { return nil }
func (b *Bridge) UsePlatformDefaultInterfaceMonitor() bool { return false }
func (b *Bridge) CreateDefaultInterfaceMonitor(logger.Logger) tun.DefaultInterfaceMonitor { return nil }
func (b *Bridge) UsePlatformNetworkInterfaces() bool { return false }
func (b *Bridge) NetworkInterfaces() ([]adapter.NetworkInterface, error) { return nil, nil }
func (b *Bridge) UnderNetworkExtension() bool { return false }
func (b *Bridge) NetworkExtensionIncludeAllNetworks() bool { return false }
func (b *Bridge) ClearDNSCache() {}
func (b *Bridge) RequestPermissionForWIFIState() error { return nil }
func (b *Bridge) ReadWIFIState(ctx context.Context) adapter.WIFIState { return adapter.WIFIState{} }
func (b *Bridge) UsePlatformConnectionOwnerFinder() bool { return false }
func (b *Bridge) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	return &adapter.ConnectionOwner{}, nil
}
func (b *Bridge) UsePlatformWIFIMonitor() bool { return false }
func (b *Bridge) UsePlatformNotification() bool { return false }
func (b *Bridge) SendNotification(notification *adapter.Notification) error { return nil }
func (b *Bridge) CancelNotification(identifier string, typeID int32) error { return nil }
func (b *Bridge) MyInterfaceAddress() []netip.Addr { return nil }
func (b *Bridge) UsePlatformNeighborResolver() bool { return false }
func (b *Bridge) StartNeighborMonitor(listener adapter.NeighborUpdateListener) error { return nil }
func (b *Bridge) CloseNeighborMonitor(listener adapter.NeighborUpdateListener) error { return nil }
func (b *Bridge) UsePlatformShell() bool { return false }
func (b *Bridge) CheckPlatformShell() error { return nil }
func (b *Bridge) OpenShellSession(user *adapter.PlatformUser, command string, env []string, term string, rows int32, cols int32) (adapter.ShellSession, error) {
	return nil, nil
}
func (b *Bridge) LookupUser(username string) (*adapter.PlatformUser, error) { return nil, nil }
func (b *Bridge) LookupSFTPServer() (string, error) { return "", nil }
func (b *Bridge) ReadSystemSSHHostKey() ([]byte, error) { return nil, nil }
func (b *Bridge) TailscaleHostname() string { return "" }
func (b *Bridge) UsePlatformBridge() bool { return false }
func (b *Bridge) CreateBridge(options adapter.BridgeOptions) (adapter.BridgeSession, error) { return nil, nil }
