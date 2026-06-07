
## Vue Web Admin 设计规范

**技术栈**: Vue 3 + Element Plus + scoped SCSS + CSS custom properties

### 颜色

| Token | 值 | 用途 |
|-------|----|------|
| primary | `#1B65A8` | 品牌主色、按钮、链接 |
| el-primary | `#409EFF` | Element Plus 默认主色 |
| bg-page | `#F4F6F9` | 页面背景 |
| bg-card | `#ffffff` | 卡片背景 |
| text-primary | `#1A2332` | 标题文字 |
| text-secondary | `#7A8599` | 辅助文字 |
| border | `#EDF2F7` | 边框、分割线 |

### 布局

- 侧栏: 展开 `220px` / 收起 `64px`
- 顶栏: `64px` 高，`backdrop-filter: blur(12px)` 毛玻璃
- 内容区: `padding: 20px`
- 过渡: 侧栏 `0.3s`，通用 `0.2s`

### 间距与圆角

- 间距基数: `4px` (4/8/12/16/20/24/32)
- 卡片圆角: `10px`
- 阴影: 蓝调 `box-shadow: 0 2px 12px rgba(27,101,168,0.06)`

### 字体

```css
font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
font-size: 14px; /* base */
```

### 组件模式

```vue
<template>
  <div class="page-container">
    <div class="page-header">
      <h2>页面标题</h2>
      <el-button type="primary">操作</el-button>
    </div>
    <el-card shadow="never" class="content-card">
      <!-- 内容 -->
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.page-container { padding: 20px; }
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.content-card { border-radius: 10px; }
</style>
```
