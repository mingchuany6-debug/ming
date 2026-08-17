# -*- coding: utf-8 -*-
import json, os, queue, socket, sqlite3, threading, time, uuid
from datetime import datetime
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

try:
    import pandas as pd
except Exception:
    raise SystemExit('请先安装 pandas openpyxl')

PORT=8765
DISCOVERY_PORT=8766
APP='高德App酒店信息补全器 V2.3.0 · 屏幕OCR版'

class StateDB:
    def __init__(self,path='amap_bridge_state_v23.db'):
        self.path=path; self.lock=threading.Lock()
        with self.con() as c:
            c.execute('''CREATE TABLE IF NOT EXISTS results(
                poi_id TEXT PRIMARY KEY, hotel_name TEXT, open_time TEXT, renovate_time TEXT,
                rooms TEXT, phone TEXT, name_check TEXT, status TEXT, evidence TEXT,
                raw_text TEXT, updated_at TEXT)'''); c.commit()
    def con(self): return sqlite3.connect(self.path,check_same_thread=False)
    def save(self,r):
        with self.lock,self.con() as c:
            c.execute('''INSERT INTO results VALUES(?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(poi_id) DO UPDATE SET
            hotel_name=excluded.hotel_name,open_time=excluded.open_time,renovate_time=excluded.renovate_time,
            rooms=excluded.rooms,phone=excluded.phone,name_check=excluded.name_check,status=excluded.status,
            evidence=excluded.evidence,raw_text=excluded.raw_text,updated_at=excluded.updated_at''',(
                r.get('poi_id',''),r.get('name',''),r.get('open_time',''),r.get('renovate_time',''),
                r.get('rooms',''),r.get('phone',''),r.get('name_check',''),r.get('status',''),
                r.get('evidence',''),r.get('raw_text',''),datetime.now().strftime('%Y-%m-%d %H:%M:%S'))); c.commit()
    def get(self,poi):
        with self.con() as c:
            c.row_factory=sqlite3.Row
            x=c.execute('select * from results where poi_id=?',(poi,)).fetchone()
            return dict(x) if x else None

class BridgeServer:
    def __init__(self,events):
        self.events=events; self.sock=None; self.client=None; self.running=False; self.lock=threading.Lock()
    def start(self):
        if self.running:return
        self.running=True
        threading.Thread(target=self._udp,daemon=True).start()
        threading.Thread(target=self._tcp,daemon=True).start()
    def _udp(self):
        s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        try:s.bind(('',DISCOVERY_PORT))
        except Exception:return
        s.settimeout(1)
        while self.running:
            try:
                data,addr=s.recvfrom(2048)
                if data==b'TUNGEE_AMAP_DISCOVER_V2':
                    s.sendto(json.dumps({'type':'TUNGEE_AMAP_PC_V2','port':PORT,'name':socket.gethostname()},ensure_ascii=False).encode(),addr)
            except socket.timeout:pass
            except Exception:pass
        try:s.close()
        except:pass
    def _tcp(self):
        self.sock=socket.socket(); self.sock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        self.sock.bind(('',PORT)); self.sock.listen(3); self.sock.settimeout(1)
        self.events.put(('server','连接服务已启动'))
        while self.running:
            try:c,addr=self.sock.accept()
            except socket.timeout:continue
            except Exception:break
            try:
                c.setkeepalive if False else None
                if self.client:
                    try:self.client.close()
                    except:pass
                self.client=c; c.settimeout(None); self.events.put(('connected',addr[0]))
                f=c.makefile('r',encoding='utf-8',newline='\n')
                for line in f:
                    if not self.running:break
                    try:self.events.put(('msg',json.loads(line)))
                    except Exception:pass
            finally:
                try:c.close()
                except:pass
                if self.client is c:self.client=None
                self.events.put(('disconnected',''))
    def send(self,obj):
        data=(json.dumps(obj,ensure_ascii=False)+'\n').encode('utf-8')
        with self.lock:
            if not self.client:raise RuntimeError('手机未连接')
            self.client.sendall(data)

def guess_col(cols,names):
    for n in names:
        if n in cols:return n
    for c in cols:
        for n in names:
            if n.lower() in str(c).lower():return c
    return ''

def local_ip():
    try:
        s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.connect(('8.8.8.8',80));ip=s.getsockname()[0];s.close();return ip
    except:return '127.0.0.1'

class App(tk.Tk):
    def __init__(self):
        super().__init__(); self.title(APP); self.geometry('1520x920'); self.configure(bg='#0f172a')
        self.df=None; self.path=''; self.cols={}; self.q=queue.Queue(); self.db=StateDB(); self.server=BridgeServer(self.q)
        self.phone=False; self.phone_acc=False; self.phone_ocr=False
        self.running=False; self.paused=False; self.single=False; self.idx=0
        self.current_task=''; self.current_obj=None; self.current_index=-1; self.task_deadline=0; self.reconnect_deadline=0
        self.build(); self.server.start(); self.after(120,self.tick)
    def build(self):
        top=tk.Frame(self,bg='#198754');top.pack(fill='x')
        tk.Label(top,text='高德App酒店信息补全器 · 屏幕OCR版 2.3.0',font=('Microsoft YaHei',22,'bold'),fg='white',bg='#198754').pack(side='left',padx=22,pady=16)
        tk.Label(top,text=f'电脑IP：{local_ip()}  端口：{PORT}',font=('Microsoft YaHei',12),fg='white',bg='#198754').pack(side='right',padx=22)
        bar=tk.Frame(self,bg='#e8f5ee');bar.pack(fill='x')
        for text,cmd,color,width in [
            ('① 导入Excel',self.import_xls,'#168754',14),('② 启动连接服务',self.start_server,'#168754',14),
            ('③ 批量OCR补全',self.start_job,'#168754',15),('测试选中1家',self.test_selected,'#3b82f6',13),
            ('暂停/继续',self.pause,'#d99b00',12),('停止',self.stop,'#d33',10),('导出结果',self.export,'#286ee8',12)]:
            tk.Button(bar,text=text,command=cmd,bg=color,fg='white',font=('Microsoft YaHei',11,'bold'),width=width,height=2).pack(side='left',padx=5,pady=9)
        self.status=tk.Label(self,text=f'连接服务已启动：{local_ip()}:{PORT}',anchor='w',font=('Microsoft YaHei',11),fg='#8ee8ae',bg='#0f172a');self.status.pack(fill='x',padx=16,pady=(8,2))
        self.mode=tk.Label(self,text='策略：酒店名搜索 → 匹配目标酒店 → 进入详情 → 手机截屏中文OCR → 识别“开业时间 YYYY年MM月DD日” → 回填Excel',anchor='w',font=('Microsoft YaHei',10),fg='#9bd7ff',bg='#0f172a');self.mode.pack(fill='x',padx=16,pady=(0,6))
        cols=('序号','酒店名称','POI_ID','开业时间','装修时间','客房数','App电话','名称校验','状态')
        self.tree=ttk.Treeview(self,columns=cols,show='headings',selectmode='browse')
        widths=[60,330,150,130,130,100,170,110,190]
        for c,w in zip(cols,widths):self.tree.heading(c,text=c);self.tree.column(c,width=w,anchor='center' if c!='酒店名称' else 'w')
        self.tree.pack(fill='both',expand=True,padx=14,pady=8)
        self.log=tk.Text(self,height=12,bg='#020617',fg='#40e28e',font=('Consolas',10));self.log.pack(fill='x',padx=14,pady=(0,10))
    def write(self,s):self.log.insert('end',f'[{time.strftime("%H:%M:%S")}] {s}\n');self.log.see('end')
    def start_server(self):self.server.start();self.status.config(text=f'连接服务已启动：{local_ip()}:{PORT}')
    def import_xls(self):
        p=filedialog.askopenfilename(filetypes=[('Excel','*.xlsx *.xls')])
        if not p:return
        try:self.df=pd.read_excel(p)
        except Exception as e:messagebox.showerror('导入失败',str(e));return
        self.path=p; cs=list(self.df.columns)
        self.cols={'poi':guess_col(cs,['高德POI_ID','POI_ID','poi_id']),'name':guess_col(cs,['酒店名称','名称','name']),
                   'lon':guess_col(cs,['经度','longitude','lon']),'lat':guess_col(cs,['纬度','latitude','lat']),
                   'address':guess_col(cs,['地址','详细地址','酒店地址','营业地址_高德','address'])}
        if not self.cols['poi'] or not self.cols['name']:messagebox.showerror('字段不全','至少需要 高德POI_ID 和 酒店名称');return
        for x in self.tree.get_children():self.tree.delete(x)
        for i,r in self.df.iterrows():
            poi=str(r.get(self.cols['poi'],'') or '').strip();name=str(r.get(self.cols['name'],'') or '').strip();old=self.db.get(poi) or {}
            self.tree.insert('','end',iid=str(i),values=(i+1,name,poi,old.get('open_time',''),old.get('renovate_time',''),old.get('rooms',''),old.get('phone',''),old.get('name_check',''),old.get('status','')))
        self.status.config(text=f'已导入 {os.path.basename(p)}｜{len(self.df)} 条')
    def ready(self):
        if self.df is None:messagebox.showwarning('提示','先导入Excel');return False
        if not self.phone:messagebox.showwarning('提示','手机还没连接电脑');return False
        if not self.phone_acc or not self.phone_ocr:messagebox.showwarning('提示','手机端V2.3无障碍/OCR未就绪，请重新开启无障碍并连接');return False
        return True
    def start_job(self):
        if not self.ready():return
        if self.current_task:return messagebox.showwarning('任务正在执行','当前酒店还没结束，不能重复启动任务')
        self.single=False;self.running=True;self.paused=False;self.idx=0;self.write('开始批量OCR补全');self.next_task()
    def test_selected(self):
        if not self.ready():return
        if self.current_task:return messagebox.showwarning('禁止任务重叠','当前正在处理一家酒店。请等它完成或先点“停止”，再测试另一家。')
        sel=self.tree.selection()
        if not sel:return messagebox.showwarning('提示','先选中一家酒店')
        self.single=True;self.running=True;self.paused=False;self.current_index=int(sel[0]);self.send_index(self.current_index)
    def task_obj(self,i):
        r=self.df.iloc[i]
        def v(k):
            c=self.cols.get(k,'');return str(r.get(c,'') or '').strip() if c else ''
        tid=str(uuid.uuid4())[:10]
        return tid,{'cmd':'task','task_id':tid,'poi_id':v('poi'),'name':v('name'),'lon':v('lon'),'lat':v('lat'),'address':v('address')}
    def send_index(self,i):
        tid,obj=self.task_obj(i);self.current_task=tid;self.current_obj=obj;self.current_index=i;self.task_deadline=time.time()+55
        try:
            self.server.send(obj);self.tree.selection_set(str(i));self.tree.see(str(i));self.status.config(text=f'OCR处理中 {i+1}/{len(self.df)}：{obj["name"]}');self.write(f'下发 {i+1}: {obj["name"]} | {obj["poi_id"]}')
        except Exception as e:self.write('发送失败：'+str(e));self.current_task='';self.running=False
    def next_task(self):
        if not self.running or self.paused or self.current_task:return
        while self.idx<len(self.df):
            i=self.idx;self.idx+=1;poi=str(self.df.iloc[i].get(self.cols['poi'],'') or '').strip();old=self.db.get(poi)
            if old and str(old.get('open_time','')).strip():continue
            self.send_index(i);return
        self.running=False;self.status.config(text='全部处理完成');self.write('全部处理完成')
    def pause(self):self.paused=not self.paused;self.write('已暂停' if self.paused else '已继续')
    def stop(self):
        try:self.server.send({'cmd':'stop'})
        except:pass
        self.running=False;self.paused=False;self.current_task='';self.current_obj=None;self.current_index=-1;self.write('已停止并清除当前任务')
    def export(self):
        if self.df is None:return
        out=filedialog.asksaveasfilename(defaultextension='.xlsx',filetypes=[('Excel','*.xlsx')],initialfile='高德App_OCR开业时间补全结果.xlsx')
        if not out:return
        d=self.df.copy()
        for c in ['高德App开业时间','高德App装修时间','高德App客房数','高德App电话','名称校验','App识别状态','App识别证据','App处理时间']:d[c]=''
        for i,r in d.iterrows():
            x=self.db.get(str(r.get(self.cols['poi'],'') or '').strip()) or {}
            d.at[i,'高德App开业时间']=x.get('open_time','');d.at[i,'高德App装修时间']=x.get('renovate_time','');d.at[i,'高德App客房数']=x.get('rooms','');d.at[i,'高德App电话']=x.get('phone','');d.at[i,'名称校验']=x.get('name_check','');d.at[i,'App识别状态']=x.get('status','');d.at[i,'App识别证据']=x.get('evidence','');d.at[i,'App处理时间']=x.get('updated_at','')
        d.to_excel(out,index=False);messagebox.showinfo('完成',out)
    def update_row(self,j):
        poi=j.get('poi_id','')
        for iid in self.tree.get_children():
            vals=list(self.tree.item(iid,'values'))
            if len(vals)>2 and str(vals[2])==poi:
                vals[3]=j.get('open_time','');vals[4]=j.get('renovate_time','');vals[5]=j.get('rooms','');vals[6]=j.get('phone','');vals[7]=j.get('name_check','');vals[8]=j.get('status','');self.tree.item(iid,values=vals);break
    def resend_current(self):
        if not self.running or not self.current_task or not self.current_obj or not self.phone:return
        try:self.server.send(self.current_obj);self.task_deadline=time.time()+55;self.write('手机已重连，自动重发当前酒店：'+self.current_obj.get('name',''))
        except Exception as e:self.write('重发失败：'+str(e))
    def handle(self,j):
        t=j.get('type')
        if t=='hello':
            self.phone=True;self.phone_acc=bool(j.get('accessibility'));self.phone_ocr=bool(j.get('ocr'))
            self.status.config(text=f'手机已连接：{j.get("device","")}｜版本 {j.get("version","")}｜无障碍={self.phone_acc}｜OCR={self.phone_ocr}')
            self.write('手机连接成功｜V2.3 OCR='+('就绪' if self.phone_ocr else '不可用'))
            if self.current_task and self.running:self.after(800,self.resend_current)
        elif t=='debug':self.write('手机：'+str(j.get('message','')))
        elif t=='task_opened':self.write('手机已打开高德，模式='+str(j.get('mode','')))
        elif t=='pong':self.phone_acc=bool(j.get('accessibility',self.phone_acc))
        elif t=='result':
            if j.get('status')=='busy_rejected':self.write('手机拒绝重叠任务：等待上一任务结束');return
            self.db.save(j);self.update_row(j)
            self.write(f'回传：{j.get("name")}｜开业={j.get("open_time")}｜房量={j.get("rooms")}｜状态={j.get("status")}｜{j.get("evidence","")}')
            if j.get('task_id')==self.current_task:
                self.current_task='';self.current_obj=None;self.current_index=-1
                if self.single:self.single=False;self.running=False;self.status.config(text='单店测试完成');return
                if self.running:self.after(450,self.next_task)
    def tick(self):
        try:
            while True:
                typ,val=self.q.get_nowait()
                if typ=='msg':self.handle(val)
                elif typ=='connected':self.write('手机TCP已连接：'+val)
                elif typ=='disconnected':
                    self.phone=False;self.phone_acc=False;self.phone_ocr=False;self.write('手机连接断开，V2.3会等待自动重连')
                    if self.current_task:self.reconnect_deadline=time.time()+25;self.task_deadline=time.time()+55
                elif typ=='server':self.write(val)
        except queue.Empty:pass
        if self.running and self.current_task and time.time()>self.task_deadline:
            self.write('当前酒店55秒无结果，已暂停；不会继续空跑。');self.running=False;messagebox.showwarning('任务暂停','当前酒店超时。请把最后几行“屏幕OCR完成/截图失败”日志发我。')
        if self.current_task and self.reconnect_deadline and time.time()>self.reconnect_deadline and not self.phone:
            self.running=False;self.reconnect_deadline=0;messagebox.showwarning('手机未重连','手机断开超过25秒，已暂停当前批量任务。')
        self.after(120,self.tick)

if __name__=='__main__':App().mainloop()
