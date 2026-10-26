# 现有 iPhone 透明渲染图（目录名沿用旧品牌 `whaleness-`）

> ⚠️ **项目已改名为 Orcarness**；本目录名是历史遗留（资源文件路径，改名需同步引用它的代码），
> 内容本身是产品渲染素材，与品牌无关。

复用 [Portrait Studio](https://portrait-studio-lichaofan.mistylynx17.chatgpt.site/) 的本地源码、真实 GLB 模型和原有透明 PNG 导出流程生成。原始截图不做修改。

本目录保留六张 2000 × 3200 的透明 PNG，无背景、无投影。网页使用 `website/assets/mockups/` 中裁去透明留白的 WebP，六张共约 443 KiB。网页中的柔和投影通过 CSS 添加，PNG 本身不含投影。

## 渲染设置

- 型号：iPhone 15 Pro Max；数量：1。
- 画布：1000 × 1600；导出倍率：2×。
- 机身：银白色 `#c7cfda`；关闭玻璃反光和投影；截图居中填满。
- `home.png`：轻盈悬浮，水平角度 −18°、俯仰 8°、画面旋转 −9°，大小 95%。
- 其余五张：正面展示，水平、俯仰和旋转均为 0°，大小 95%。
- 截图来源对应 `website/README.md`；WebP 裁切位置与尺寸见 `website/assets/mockups/render-settings.json`。

## 模型来源与许可

**Apple iPhone 15 Pro Max Black**，作者 [polyman](https://sketchfab.com/Polyman_3D)，[原始模型](https://sketchfab.com/3d-models/apple-iphone-15-pro-max-black-df17520841214c1792fb8a44c6783ee7)，[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。制作时修改机身材质、颜色、灯光和视角，并替换屏幕贴图。模型非 Apple 官方素材。素材随附许可记录见 `website/assets/mockups/ATTRIBUTION.txt`。
