## 功能更新

- 支持在会话中选择并上传文件，查看上传进度、移除附件或重试失败上传；已发送文件以附件卡片展示。
- 双端新增独立的正文文字选择页面，长按进入后可选择、全选和复制，关闭后保留原会话阅读位置。
- 统一 Android 菜单的圆角毛玻璃、轻阴影与动效，改善文字操作菜单、工作区和设置弹层的一致性。
- 修复 iOS 点击「查看完整团队内容」后需等待菜单收回才弹出面板的问题，保留面板上滑动画，并避免关闭后团队按钮隐藏。
- 改善 iOS 从后台恢复时的连接验证与请求超时处理，减少误报，并提升相册图片发送的格式兼容性。

## 必需插件

- 必须安装 [dsh-plugin-mobile-gateway](https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway) `v0.11.1` 或更高版本。
- 安装或升级后，需要完整退出并重新启动 `dsh web`。

```bash
dsh plugin --profile web add dsh-plugin-mobile-gateway@latest
```
