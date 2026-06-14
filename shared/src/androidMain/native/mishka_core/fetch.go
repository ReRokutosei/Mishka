package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path"
	P "path"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/component/age"
	clashHttp "github.com/metacubex/mihomo/component/http"
	"github.com/metacubex/mihomo/config"
)

const fetchTimeout = 60 * time.Second

// JSON-序列化后跨 JNI 边界回 Kotlin，字段必须与 [data.bridge.CoreFetchProgress] 对齐。
type FetchProgress struct {
	Action      string   `json:"action"`
	Args        []string `json:"args"`
	Progress    int      `json:"progress"`
	MaxProgress int      `json:"max"`
}

type FetchResult struct {
	Upload   int64 `json:"upload"`
	Download int64 `json:"download"`
	Total    int64 `json:"total"`
	Expire   int64 `json:"expire"`
}

//export mishkaFetchAndValid
//
// 成功返回 JSON FetchResult 的 C.CString；失败返回 "error: ..."；NULL 表示参数非法。
// 调用方必须 mishkaFreeString 释放返回值。进度由 mishkaQueryProgress 轮询。
// userAgent 留空时退回 mishkaCoreInit 设置的全局默认 UA。
func mishkaFetchAndValid(
	cWorkDir *C.char,
	cURL *C.char,
	force C.int,
	cHttpProxy *C.char,
	cUserAgent *C.char,
	token C.int,
) *C.char {
	workDir := C.GoString(cWorkDir)
	rawURL := C.GoString(cURL)
	httpProxy := C.GoString(cHttpProxy)
	userAgent := C.GoString(cUserAgent)

	ctx, cancel := context.WithCancel(context.Background())
	cancelRegistry.Store(int32(token), func() { cancel() })
	defer cancelRegistry.Delete(int32(token))
	defer progressStore.Delete(int32(token))

	result, err := runFetchAndValid(ctx, int32(token), workDir, rawURL, force != 0, httpProxy, userAgent)
	if err != nil {
		return C.CString("error: " + err.Error())
	}
	payload, _ := json.Marshal(result)
	return C.CString(string(payload))
}

func runFetchAndValid(
	ctx context.Context,
	token int32,
	workDir, rawURL string,
	force bool,
	httpProxy string,
	userAgent string,
) (*FetchResult, error) {
	if err := os.MkdirAll(workDir, 0700); err != nil {
		return nil, fmt.Errorf("create workDir: %w", err)
	}
	// 空 UA 退回 mishkaCoreInit 设置的全局默认 —— 用户没显式覆写时仍走 ClashMetaForAndroid
	effectiveUA := strings.TrimSpace(userAgent)
	if effectiveUA == "" {
		effectiveUA = currentUserAgent()
	}

	// mihomo HTTP / GeoIP downloadToPath 读 env；processLock 串行保证 set/unset 不被并发污染。
	if httpProxy != "" {
		_ = os.Setenv("HTTPS_PROXY", httpProxy)
		_ = os.Setenv("HTTP_PROXY", httpProxy)
		defer os.Unsetenv("HTTPS_PROXY")
		defer os.Unsetenv("HTTP_PROXY")
	}

	configPath := P.Join(workDir, "config.yaml")
	result := &FetchResult{}

	if force {
		_ = os.Remove(configPath)
	}
	if _, err := os.Stat(configPath); os.IsNotExist(err) {
		u, err := url.Parse(rawURL)
		if err != nil {
			return nil, fmt.Errorf("parse url: %w", err)
		}
		setProgress(token, FetchProgress{
			Action:      "FetchConfiguration",
			Args:        []string{u.Host},
			Progress:    -1,
			MaxProgress: -1,
		})
		if err := fetchURL(ctx, u, configPath, result, effectiveUA); err != nil {
			return nil, err
		}
	}

	raw, err := os.ReadFile(configPath)
	if err != nil {
		return nil, fmt.Errorf("read config: %w", err)
	}
	// age：订阅若是 age armor 加密，用全局密钥（mishkaSetAgeSecretKey 设置）解密后落盘明文，
	// 运行时直接加载明文无需再带密钥；非加密内容 DecryptBytes 原样返回。
	if decrypted, derr := age.DecryptBytes(raw); derr != nil {
		return nil, fmt.Errorf("decrypt config: %w", derr)
	} else if !bytes.Equal(decrypted, raw) {
		if werr := os.WriteFile(configPath, decrypted, 0600); werr != nil {
			return nil, fmt.Errorf("write decrypted config: %w", werr)
		}
		raw = decrypted
	}
	rawCfg, err := config.UnmarshalRawConfig(raw)
	if err != nil {
		return nil, fmt.Errorf("unmarshal config: %w", err)
	}

	providersDir := P.Join(workDir, "providers")
	if err := os.MkdirAll(providersDir, 0700); err != nil {
		return nil, fmt.Errorf("create providers dir: %w", err)
	}
	patchProvidersPath(rawCfg, providersDir)

	prefetchProviders(ctx, token, rawCfg, effectiveUA)

	if ctx.Err() != nil {
		return nil, ctx.Err()
	}

	setProgress(token, FetchProgress{
		Action:      "Verifying",
		Args:        []string{},
		Progress:    0xffff,
		MaxProgress: 0xffff,
	})
	cfg, err := config.ParseRawConfig(rawCfg)
	if err != nil {
		return nil, fmt.Errorf("validate config: %w", err)
	}
	destroyProviders(cfg)

	return result, nil
}

func setProgress(token int32, p FetchProgress) {
	if bytes, err := json.Marshal(p); err == nil {
		progressStore.Store(token, string(bytes))
	}
}

func fetchURL(ctx context.Context, u *url.URL, dest string, result *FetchResult, userAgent string) error {
	scheme := strings.ToLower(u.Scheme)
	if scheme != "http" && scheme != "https" {
		return fmt.Errorf("unsupported scheme %s", u.Scheme)
	}

	subCtx, cancel := context.WithTimeout(ctx, fetchTimeout)
	defer cancel()

	header := http.Header{"User-Agent": []string{userAgent}}
	resp, err := clashHttp.HttpRequest(subCtx, u.String(), http.MethodGet, header, nil)
	if err != nil {
		return fmt.Errorf("http request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("http status %d", resp.StatusCode)
	}

	parseUserinfo(resp.Header.Get("subscription-userinfo"), result)

	if err := os.MkdirAll(P.Dir(dest), 0700); err != nil {
		return err
	}
	f, err := os.OpenFile(dest, os.O_WRONLY|os.O_TRUNC|os.O_CREATE, 0600)
	if err != nil {
		return err
	}
	defer f.Close()

	if _, err := io.Copy(f, resp.Body); err != nil {
		_ = os.Remove(dest)
		return err
	}
	if fi, err := os.Stat(dest); err != nil || fi.Size() == 0 {
		_ = os.Remove(dest)
		return errors.New("empty response body")
	}
	return nil
}

// subscription-userinfo 头格式：upload=xxx;download=yyy;total=zzz;expire=aaa
func parseUserinfo(header string, result *FetchResult) {
	if header == "" {
		return
	}
	for _, segment := range strings.Split(header, ";") {
		kv := strings.SplitN(strings.TrimSpace(segment), "=", 2)
		if len(kv) != 2 {
			continue
		}
		v, err := strconv.ParseInt(kv[1], 10, 64)
		if err != nil {
			continue
		}
		switch strings.ToLower(kv[0]) {
		case "upload":
			result.Upload = v
		case "download":
			result.Download = v
		case "total":
			result.Total = v
		case "expire":
			result.Expire = v
		}
	}
}

// 把每个 provider 的 path 改写成 workDir/providers/<basename> 绝对路径，
// Parse 时 mihomo 用 constant.Path.Resolve(provider.path) 解析，相对路径会落到 homeDir 之外。
func patchProvidersPath(cfg *config.RawConfig, providersDir string) {
	forEachProviders(cfg, func(_, _ int, _ string, provider map[string]any, _ string) {
		urlStr, _ := provider["url"].(string)
		pathStr, _ := provider["path"].(string)
		if urlStr == "" && pathStr == "" {
			return
		}
		fileName := utils.MakeHash([]byte(urlStr)).String()
		if pathStr != "" {
			fileName = filepath.Base(pathStr)
		}
		provider["path"] = P.Join(providersDir, fileName)
	})
}

// best-effort：单个下载失败不阻塞 Parse；缺 provider 时 Parse 自己会报错。
func prefetchProviders(ctx context.Context, token int32, cfg *config.RawConfig, userAgent string) {
	type item struct {
		key  string
		url  string
		dest string
	}
	var items []item
	forEachProviders(cfg, func(_, _ int, key string, provider map[string]any, _ string) {
		urlStr, _ := provider["url"].(string)
		dest, _ := provider["path"].(string)
		if urlStr == "" || dest == "" {
			return
		}
		if _, err := os.Stat(dest); err == nil {
			return
		}
		items = append(items, item{key: key, url: urlStr, dest: dest})
	})

	total := len(items)
	for i, it := range items {
		if ctx.Err() != nil {
			return
		}
		setProgress(token, FetchProgress{
			Action:      "FetchProviders",
			Args:        []string{it.key},
			Progress:    i,
			MaxProgress: total,
		})
		u, err := url.Parse(it.url)
		if err != nil {
			continue
		}
		_ = fetchProvider(ctx, u, it.dest, userAgent)
	}
}

func fetchProvider(ctx context.Context, u *url.URL, dest string, userAgent string) error {
	subCtx, cancel := context.WithTimeout(ctx, fetchTimeout)
	defer cancel()
	header := http.Header{"User-Agent": []string{userAgent}}
	resp, err := clashHttp.HttpRequest(subCtx, u.String(), http.MethodGet, header, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("status %d", resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	// best-effort：provider 若用全局 age 密钥加密则解密后落盘；用 per-provider age-secret-key
	// 字段加密的（DecryptBytes 因无匹配身份报错）保持原样，由 mihomo 运行时按字段解密。
	if decrypted, derr := age.DecryptBytes(body); derr == nil {
		body = decrypted
	}
	if err := os.MkdirAll(path.Dir(dest), 0700); err != nil {
		return err
	}
	if err := os.WriteFile(dest, body, 0600); err != nil {
		return err
	}
	return nil
}

func forEachProviders(cfg *config.RawConfig, fn func(index, total int, key string, provider map[string]any, kind string)) {
	total := len(cfg.ProxyProvider) + len(cfg.RuleProvider)
	idx := 0
	for k, v := range cfg.ProxyProvider {
		fn(idx, total, k, v, "proxies")
		idx++
	}
	for k, v := range cfg.RuleProvider {
		fn(idx, total, k, v, "rules")
		idx++
	}
}

func destroyProviders(cfg *config.Config) {
	for _, p := range cfg.Providers {
		if c, ok := any(p).(io.Closer); ok {
			_ = c.Close()
		}
	}
	for _, p := range cfg.RuleProviders {
		if c, ok := any(p).(io.Closer); ok {
			_ = c.Close()
		}
	}
}

func currentUserAgent() string {
	v := mishkaUserAgent.Load()
	if s, ok := v.(string); ok && s != "" {
		return s
	}
	return "Mishka/dev"
}
