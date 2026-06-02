# test-scan-vedio
方案一：使用 Personal Access Token 代替密码（推荐新手）
1. 生成一个 Token
登录 GitHub，点击右上角头像 → Settings → 左下角 Developer settings → Personal access tokens → Tokens (classic) → Generate new token (classic)
给 Token 起一个描述性名称（例如 git-push）
设置过期时间（建议不超过 1 年）
选择权限范围：至少勾选 repo（完全控制私有仓库）或根据需要勾选其他权限
点击 Generate token，立即复制生成的 token 字符串（只显示一次，丢失后需重新生成）

2. 在 Git 推送时使用 Token
当你执行 git push 并提示 Password for 'https://...' 时：
Username：仍然输入你的 GitHub 用户名（vicenteshi）
Password：粘贴刚刚生成的 token（不是你的登录密码）
之后 Git 通常会缓存该凭据，下次不再询问。
