import sys, random, time
from sim import *

entries=[]
for line in open(DICT,encoding='utf-8'):
    p=line.rstrip('\n').split('\t')
    if len(p)>=3:
        try: entries.append((int(p[2]),p[0],p[1]))
        except: pass
# 真實詞注音前綴集合（去聲調、去空白）
PREF=set()
for w,x,zy in entries:
    c=''.join(ch for ch in zy if ch not in TONES and ch!=' ')
    for i in range(1,len(c)+1): PREF.add(c[:i])
def natural(c):
    return c.replace(' ','') in PREF and ' ' not in c.strip() or c.strip().replace(' ','') in PREF and False

multi=sorted([e for e in entries if 2<=len(e[1])<=4],reverse=True)
uniq=[];s=set()
for w,x,zy in multi:
    if x in s: continue
    s.add(x);uniq.append((x,zy))
random.seed(7)
setA=random.sample(uniq[:3000],200)          # 常用詞
setB=random.sample(uniq[3000:30000],200)     # 中頻詞
common2=[e for e in uniq[:1500] if len(e[0])==2]
setC=[]
for _ in range(200):
    a=random.choice(common2); b=random.choice(common2)
    setC.append((a[0]+b[0], a[1]+b[1]))       # 兩詞連打（4字）
for _ in range(100):
    a=random.choice(common2); b=random.choice(common2); c=random.choice(common2)
    setC.append((a[0]+b[0]+c[0], a[1]+b[1]+c[1]))  # 三詞連打（6字）

def evalset(runner, label, test, partial=True):
    t1=t5=0; bad=tot=0; oddtop=0; n=0; p5=0; pn=0
    for x,zy in test:
        ks=zk(zy)
        cl,combos,_=runner(ks)
        ws=[e.word for e in cl]
        t1+= ws[:1]==[x]; t5+= x in ws[:5]
        if ws and ws[0]!=x: oddtop+=1
        n+=1
        for c in combos:
            tot+=1; bad+= (c.replace(' ','') not in PREF) or (' ' in c)
        if partial and len(ks)>=5:
            cl2,combos2,_=runner(ks[:-1]); ws2=[e.word for e in cl2]
            pn+=1
            # 少打一鍵時，期待前綴詞(去掉最後一字)或原詞出現在前5
            p5+= (x in ws2[:5]) or (x[:-1] in ws2[:5])
            for c in combos2:
                tot+=1; bad+= (c.replace(' ','') not in PREF) or (' ' in c)
    print(f"[{label}] top1={t1/n:5.1%} top5={t5/n:5.1%} | 少一鍵top5={p5/max(pn,1):5.1%} | 左側不自然組合={bad}/{tot}={bad/tot:5.1%}")

def bench(runner,label):
    print("---",label)
    evalset(runner,"A 常用詞",setA)
    evalset(runner,"B 中頻詞",setB)
    evalset(runner,"C 連打句",setC)
if __name__=='__main__':
    bench(run,"現行")
