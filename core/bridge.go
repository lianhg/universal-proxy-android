// Package bridge 把 sing-box 以内嵌方式跑在 App 进程内（安卓不能 spawn 子进程）。
// 只暴露 gomobile 可绑定的签名：string / int32 / bool / error。
package bridge

import (
	"context"
	"fmt"
	"sync"

	"github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"

	"universalproxy.app/core/tunbridge"
)

type instance struct {
	box *box.Box
}

var (
	mu  sync.Mutex
	cur *instance
)

// baseContext 注册全部协议/传输/DNS 服务注册表。
// 关键：配置解码（UnmarshalExtendedContext）也必须在带注册表的 ctx 下进行，
// 否则 dns.servers 的 type（udp/https 等）会报 "missing DNS transport options registry"。
func baseContext(fd int) context.Context {
	ctx := include.Context(context.Background())
	return service.ContextWith[adapter.PlatformInterface](ctx, &tunbridge.Bridge{FD: fd})
}

// Start 用 sing-box JSON 配置 + VpnService 的 tun fd 启动内核。
func Start(configJSON string, tunFd int32) error {
	mu.Lock()
	defer mu.Unlock()
	if cur != nil {
		return fmt.Errorf("already running; call Stop first")
	}
	ctx := baseContext(int(tunFd))
	options, err := json.UnmarshalExtendedContext[option.Options](ctx, []byte(configJSON))
	if err != nil {
		return fmt.Errorf("decode config: %w", err)
	}
	b, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		return fmt.Errorf("create box: %w", err)
	}
	if err := b.PreStart(); err != nil {
		_ = b.Close()
		return fmt.Errorf("prestart: %w", err)
	}
	if err := b.Start(); err != nil {
		_ = b.Close()
		return fmt.Errorf("start: %w", err)
	}
	cur = &instance{box: b}
	return nil
}

// Stop 关闭内核。
func Stop() error {
	mu.Lock()
	defer mu.Unlock()
	if cur == nil {
		return nil
	}
	err := cur.box.Close()
	cur = nil
	return err
}

// IsRunning 供 UI 轮询。
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return cur != nil
}

// CheckConfig 校验配置（不启动），返回错误串，空串表示通过。
func CheckConfig(configJSON string) string {
	_, err := json.UnmarshalExtendedContext[option.Options](baseContext(0), []byte(configJSON))
	if err != nil {
		return err.Error()
	}
	return ""
}
