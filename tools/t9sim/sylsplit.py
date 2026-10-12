import math, random
from sim import *
import bench2
from bench2 import uniq
TOT=sum(SYL_W.values()); LT=math.log(TOT)
def syl_cost(s,pen):
    return LT-math.log(max(SYL_W.get(s,1),1))+pen
def splits(keys,pen,beam=6):
    n=len(keys)
    dp=[[] for _ in range(n+1)]; dp[0]=[("",0.0)]
    for i in range(1,n+1):
        c=[]
        for ln in range(1,min(3,i)+1):
            j=i-ln
            if not dp[j]: continue
            m=EXACT_KEY_MAP.get(tuple(keys[j:i]))
            if not m: continue
            for s in m:
                if SYL_W.get(s,0)==0: continue          # 沒有任何字使用的音節不考慮
                for pt,ps in dp[j]: c.append((pt+(" " if pt else "")+s,ps+syl_cost(s,pen)))
        c.sort(key=lambda x:x[1]); seen=set(); keep=[]
        for t,sc in c:
            if t in seen: continue
            seen.add(t); keep.append((t,sc))
            if len(keep)>=beam: break
        dp[i]=keep
    return [t.replace(' ','') for t,_ in dp[n]]
random.seed(5)
test=[(x,zy) for x,zy in random.sample(uniq[:30000],400) if 4<=len([c for c in zy if c not in TONES])<=8 and len(x)>=2]
print("測試詞數",len(test))
for pen in (0,2,4,6,8,10):
    h1=h3=h5=0
    for x,zy in test:
        clean=''.join(c for c in zy if c not in TONES); ks=zk(clean)
        r=splits(ks,pen); 
        h1+= clean in r[:1]; h3+= clean in r[:3]; h5+= clean in r[:5]
    print(f"pen={pen:2d}: 正確注音組合 top1={h1/len(test):5.1%} top3={h3/len(test):5.1%} top5={h5/len(test):5.1%}")
# 現行 step3
h=0
for x,zy in test:
    clean=''.join(c for c in zy if c not in TONES); ks=zk(clean)
    if len(ks)<=6: pass
print("現行 step3 (4~6鍵,8名內) 命中率見先前量測: 4/172")
print("幫我 ㄅㄤㄨㄛ:", {pen:splits(zk('ㄅㄤㄨㄛ'),pen)[:5] for pen in (4,6,8)})
