## 微信小程序设计规范

**技术栈**: WXML + WXSS + CSS vars on `page{}` + ColorUI v2.1.4

### 颜色 — 双主题

| Token | 消费端 (C端) | 商家端 (B端) |
|-------|-------------|-------------|
| primary | `#52c41a` 绿 | `#C9A86C` 金 |
| bg-page | `#f5f5f5` | `#f5f5f5` |
| bg-card | `#ffffff` | `#ffffff` |
| text-primary | `#333333` | `#333333` |
| text-secondary | `#666666` | `#666666` |
| text-hint | `#aaaaaa` | `#aaaaaa` |
| tab-active | `#2967ff` | `#C9A86C` |

商家端 Header: `linear-gradient(135deg, #1A1A1A, #2D2D2D)` 黑金主题。

### 布局

- 导航栏背景: `#fefefe`，文字: black
- Header 高度: 约 `88rpx` (含状态栏)
- TabBar: 4 tabs，底色 `#ffffff`
- 安全区: `padding-top: constant(safe-area-inset-top); padding-top: env(safe-area-inset-top);`

### 间距与圆角 (rpx)

- 内容间距: `30rpx`
- 区块间距: `20rpx`
- 卡片圆角: `16rpx`
- 标准圆角: `6rpx`
- 全圆: `5000rpx`
- 阴影: `0 4rpx 20rpx rgba(0,0,0,0.08)`

### 字体

- 默认: `28rpx`
- 标题: `34rpx` bold
- 辅助: `24rpx`
- 小字: `22rpx`
- 大图标: `44rpx`

### 组件模式

```html
<view class="container">
  <view class="card">
    <text class="card-title">标题</text>
    <text class="card-desc">描述文字</text>
  </view>
</view>
```

```css
.container { padding: 0 20rpx; background: #f5f5f5; }
.card {
  background: #fff;
  border-radius: 16rpx;
  padding: 30rpx;
  margin-bottom: 20rpx;
  box-shadow: 0 4rpx 20rpx rgba(0,0,0,0.08);
}
.card-title { font-size: 34rpx; font-weight: bold; color: #333; }
.card-desc { font-size: 24rpx; color: #666; margin-top: 10rpx; }
```

ColorUI 类名可直接使用: `cu-card`, `cu-bar`, `cu-avatar`, `cu-tag`, `cu-btn`。

