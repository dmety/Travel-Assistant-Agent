from PIL import Image,ImageDraw,ImageFont
from pathlib import Path
root=Path(__file__).resolve().parents[2]; out=root/'docs/assets'
f='C:/Windows/Fonts/msyh.ttc'
def setup(title,sub):
 global im,d
 im=Image.new('RGB',(2400,1600),'white');d=ImageDraw.Draw(im);txt(65,35,title,46,'#15344f');txt(65,106,sub,25,'#53667a')
def txt(x,y,s,n=25,c='#263b50'):d.text((x,y),s,font=ImageFont.truetype(f,n),fill=c)
def node(x,y,w,h,name,kind,desc,external=False):
 c='#6a7582' if external else '#24639b';d.rectangle((x,y,x+w,y+h),fill=c)
 for j,(s,n) in enumerate([(name,31),(kind,21),(desc,24)]):
  lines=s.split('\n'); yy=y+22+[0,49,92][j]
  for k,line in enumerate(lines):
   font=ImageFont.truetype(f,n); ww=d.textbbox((0,0),line,font=font)[2];d.text((x+(w-ww)/2,yy+k*35),line,font=font,fill='white')
def boundary(x,y,w,h,label):
 for xx in range(x,x+w,24):d.line((xx,y,min(xx+12,x+w),y),fill='#6c8093',width=2);d.line((xx,y+h,min(xx+12,x+w),y+h),fill='#6c8093',width=2)
 for yy in range(y,y+h,24):d.line((x,yy,x,min(yy+12,y+h)),fill='#6c8093',width=2);d.line((x+w,yy,x+w,min(yy+12,y+h)),fill='#6c8093',width=2)
 txt(x+18,y+15,label,26)
def arrow(pts,label,x,y):
 d.line(pts,fill='#4d5e70',width=3);a,b=pts[-1];p,q=pts[-2]
 tri=[(a,b),(a-13,b-7),(a-13,b+7)] if a>p else ([(a,b),(a+13,b-7),(a+13,b+7)] if a<p else [(a,b),(a-7,b-13),(a+7,b-13)])
 d.polygon(tri,fill='#4d5e70')
 for i,s in enumerate(label.split('\n')):txt(x,y+32*i,s,23)
setup('旅行助手 · C4 容器图','Level 2  |  系统边界、运行单元与通信关系  |  目标架构')
boundary(470,220,1280,1170,'旅行助手 [Software System]')
node(55,540,340,205,'学生 / 同行成员','[Person]','创建、邀请、编辑\n共同旅行方案',True)
node(550,300,1120,190,'控制台客户端','[Container: Java CLI]','菜单、自然语言交互、草案确认与方案展示\n每位用户的电脑运行一个客户端实例')
node(550,720,1120,220,'旅行助手后端服务','[Container: Java / Spring Boot]','账号与权限、共享方案、Agent 编排、预算与时间校验\n单实例统一处理方案版本和文件写入')
node(550,1150,1120,180,'文本数据存储','[Data Store: UTF-8 JSON Files]','服务端本地保存账号、邀请、方案与协作数据')
node(1850,550,490,205,'DeepSeek','[External Software System]','理解需求、生成草案\n接收工具结果',True)
node(1850,1000,490,205,'高德 Web 服务','[External Software System]','V2 起提供\n地点、天气与路线查询',True)
arrow([(225,540),(225,390),(550,390)],'操作控制台\n[键盘 / 文本]',70,260)
arrow([(1100,490),(1100,720)],'业务请求 / 会话 Cookie\n[HTTPS / JSON]',1140,555)
arrow([(1100,940),(1100,1150)],'读取与持久化\n[本地文件 I/O]',1140,1005)
arrow([(1670,780),(1780,780),(1780,650),(1850,650)],'模型推理\n[HTTPS / JSON]',1785,415)
arrow([(1670,880),(1780,880),(1780,1100),(1850,1100)],'工具查询\n[HTTPS / JSON]',1790,1250)
txt(65,1450,'图例：蓝色 = 系统内运行单元 / 存储；灰色 = 人员 / 外部系统；虚线 = 系统边界。',25)
txt(65,1500,'箭头表示调用方向，结果沿原连接返回；本图不表示业务处理先后顺序。',25)
im.save(out/'旅行助手-C4容器图.png',dpi=(200,200))
setup('旅行助手 · 后端组件图','Level 3  |  放大后端服务：接口、应用编排、领域规则与适配器  |  目标架构')
boundary(65,170,2270,1310,'旅行助手后端服务 [Container: Java / Spring Boot]')
node(180,260,2040,155,'HTTP API 与访问控制','[Component: Controllers / Authentication]','账号、方案、邀请与 Agent 接口；认证、请求校验和错误响应')
node(180,550,1130,200,'方案与协作服务','[Component: Application Services]','权限检查、邀请处理、方案保存与版本冲突\nV2 留言记录；V3 投票、归档与导出')
node(1450,550,770,200,'Agent 编排服务','[Component: Agent Orchestrator]','V1 补问 / 本地工具 / 草案校验\nV2 高德工具；V3 综合建议')
node(180,880,1130,180,'领域模型与规则','[Component: Domain Model]','方案及两种类型、成员、安排与费用\n预算计算、日期与时间逻辑校验')
node(1450,880,770,180,'模型与工具适配器','[Component: External Adapters]','DeepSeek / 高德 HTTP 客户端\n超时、来源记录与调用错误处理')
node(180,1210,1130,180,'文本文件仓储','[Component: JSON Repository]','版本比较、串行写入与安全替换\n持久化账号、邀请和方案等数据')
node(1450,1210,770,180,'认证与配置支撑','[Component: Infrastructure]','BCrypt、HttpSession、配置与日志')
arrow([(700,415),(700,550)],'调用',740,460)
arrow([(1840,415),(1840,550)],'调用',1880,460)
arrow([(1450,650),(1310,650)],'确认后保存',1315,601)
arrow([(700,750),(700,880)],'使用规则',740,785)
arrow([(1835,750),(1835,880)],'推理 / 查询',1875,785)
# 应用服务直接调用仓储，连线从左侧绕过去，不经过领域模型。
arrow([(180,650),(120,650),(120,1300),(180,1300)],'持久化',185,1110)
txt(80,1510,'领域模型不调用外部服务或文件。应用服务通过仓储接口访问存储，适配器负责外部交互。',25)
im.save(out/'旅行助手-后端组件图.png',dpi=(200,200))
