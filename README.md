# test-scan-vedio
背景：扫描文件夹，递归获取视频和图片文件，收集文件信息并保存到数据库中。
Program arguments：/Users/vicente/Documents/个人/手机文件
jar包执行：java -jar test-scan-vedio.jar /Users/vicente/Documents/个人/手机文件

## 数据覆盖保存
ON DUPLICATE KEY UPDATE 是 MySQL 的扩展语法，它的作用是：当尝试插入一条记录时，如果违反了唯一约束（主键或唯一索引），则执行 UPDATE 部分指定的更新操作；如果没有冲突，则正常插入新记录。
批量保存方法batchInsertOrUpdate添加了ON DUPLICATE KEY UPDATE，file_path是唯一索引，所以同路径文件保存时是更新数据即覆盖，不会插入新数据。


## 其他
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

## 新增web分支
使用以下命令创建并切换到新分支
git checkout -b web
本想在这个分支中引入springboot，将项目改为springboot项目，再添加几个前端页面。
但突然发现需要改造的地方太多，还是直接新增一个springboot项目比较好
