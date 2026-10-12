"""九鍵 (T9) 引擎的 Python 模擬器 —— 核心模組。

把 ZhuyinT9Engine.kt 的『舊版 v2.4.3』排序邏輯 (run) 與 Trie / 詞庫載入逐行移植成 Python，
讓你不用 build APK、不用接手機，幾秒內就能量化比較演算法改動。
新版邏輯在 v2.py / v3.py / sylsplit.py；評測集在 bench2.py / sents.py。
詳見 docs/DEV_TESTING_GUIDE.md。注意：這是『近似』移植，最終一定要以真機結果為準。
"""
import os, re, math, sys, itertools
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')
# 專案根目錄：預設為 tools/t9sim/ 往上兩層；也可用環境變數 T9_REPO 覆寫
REPO = Path(os.environ.get("T9_REPO") or Path(__file__).resolve().parents[2])
DICT = str(REPO / "app/src/main/assets/dict_tw.txt")

KEYS = {1:'ㄅㄉㄚ',2:'ㄍㄐㄞ',3:'ㄓㄗㄢㄦ',4:'ㄆㄊㄛ',5:'ㄎㄑㄟ',6:'ㄔㄘㄣㄧ',7:'ㄇㄋㄜ',8:'ㄏㄒㄠㄡ',9:'ㄕㄙㄤㄨ',10:'ㄈㄌㄝ',11:'ˇˋˊ˙',12:'ㄖㄥㄩ'}
C2K = {c:k for k,cs in KEYS.items() for c in cs}
TONES = 'ˇˋˊ˙'
def seq(zy, ignore_tones):
    out=[]
    for ch in zy:
        k=C2K.get(ch)
        if k is None: continue
        if ignore_tones and k==11: continue
        out.append(k)
    return out

# 合法音節直接從 Kotlin 原始碼 (SyllableManager.VALID_SYLLABLES) 讀取，確保與 App 一致
src = open(REPO / "app/src/main/java/com/bopomofo/t9ime/engine/SyllableManager.kt", encoding='utf-8').read()
blk = src[src.index('VALID_SYLLABLES: List<String> = listOf(')+40: src.index('private val EXACT_KEY_MAP')]
VALID = re.findall(r'"([^"]+)"', blk)
VALID_SET = set(VALID)
EXACT_KEY_MAP = {}
for s in VALID:
    EXACT_KEY_MAP.setdefault(tuple(seq(s,True)), []).append(s)

class E:
    __slots__=('word','zy','w','tol')
    def __init__(s,word,zy,w,tol=False): s.word=word; s.zy=zy; s.w=w; s.tol=tol

class Node:
    __slots__=('ch','ex')
    def __init__(s): s.ch={}; s.ex={}

root=Node()
def ins_seq(sq, e):
    n=root
    for k in sq:
        nx=n.ch.get(k)
        if nx is None: nx=n.ch[k]=Node()
        n=nx
    old=n.ex.get(e.word)
    if old is None or e.w>old.w: n.ex[e.word]=e

def cross(zy):
    res=[]
    for a,b in (("ㄣ","ㄥ"),("ㄥ","ㄣ"),("ㄢ","ㄤ"),("ㄤ","ㄢ")):
        if a in zy:
            alt=zy.replace(a,b)
            for it in (True,False):
                s=seq(alt,it)
                if s and s not in res: res.append(s)
    return res

def insert(e):
    a=seq(e.zy,True)
    if a: ins_seq(a,e)
    b=seq(e.zy,False)
    if b and b!=a: ins_seq(b,e)
    if len(e.word)<=3 and e.w>=60 and not e.tol:
        for alt in cross(e.zy):
            if alt!=a and alt!=b:
                ins_seq(alt, E(e.word,e.zy,int(e.w*0.35),True))

SYL_W={}
total=0
n=0
for line in open(DICT,encoding='utf-8'):
    p=line.rstrip('\n').split('\t')
    if len(p)<3: continue
    try: w=int(p[2])
    except: w=100
    e=E(p[0],p[1],w)
    insert(e); total+=w; n+=1
    if len(p[0])==1:
        c=''.join(ch for ch in p[1] if ch not in TONES)
        SYL_W[c]=SYL_W.get(c,0)+w
LOGTOTAL=math.log(max(total,1))
print("loaded",n,"total",total,"logTotal",LOGTOTAL)

def node(sq):
    n=root
    for k in sq:
        n=n.ch.get(k)
        if n is None: return None
    return n

def search_exact(sq, tol=True):
    nd=node(sq)
    if not nd: return []
    es=[e for e in nd.ex.values() if tol or not e.tol]
    return sorted(es,key=lambda e:(not e.tol,e.w),reverse=True)

def collect(nd,res,depth,maxd,tol):
    if depth>maxd or len(res)>=50: return
    for k,c in nd.ch.items():
        rem=50-len(res)
        if rem<=0: return
        v=[e for e in c.ex.values() if tol or not e.tol]
        res.extend(v[:rem])
        collect(c,res,depth+1,maxd,tol)

def search_prefix(sq,maxd=3,tol=True):
    nd=node(sq)
    if not nd: return []
    exw=set(nd.ex.keys())
    res=[]
    collect(nd,res,0,maxd,tol)
    seen=set(); out=[]
    for e in res:
        if e.word in seen: continue
        seen.add(e.word)
        if e.word in exw or (not tol and e.tol): continue
        out.append(e)
    return sorted(out,key=lambda e:(not e.tol,e.w),reverse=True)

# user dictionary stub (word -> (zhuyin,count))
USER={}
def boost(word,age_recent=True):
    u=USER.get(word)
    if not u: return 0
    base=(1_000_000+min(u[1]*100_000,10_000_000)) if len(word)==1 else (2_000_000+min(u[1]*200_000,20_000_000))
    return base+3_000_000

def learn(word,zy,cnt=1):
    USER[word]=(zy,USER.get(word,('',0))[1]+cnt)
    w=50_000_000+min(USER[word][1]*1_000_000,50_000_000)
    insert(E(word,zy,w))

def eff_weight(e,inkeys,exact,prev=None):
    ub=boost(e.word)
    if exact and ub>0: return 1e9+ub
    raw=float(e.w)
    if not exact and ub>0: raw*=(1.0+min(ub,2_000_000)/1_000_000.0)
    L=len(e.word)
    if exact: lm={2:2.5,3:2.2,4:2.0,1:1.5}.get(L,1.8)
    else: lm={2:1.2,3:1.0,4:0.9,1:0.8}.get(L,0.8)
    tk=len(seq(e.zy,True))
    dist=max(0,tk-inkeys)
    dm=2.5 if exact else 0.65**dist
    cm=1.3 if (prev and e.word.startswith(prev)) else 1.0
    tm=0.35 if e.tol else 1.0
    return raw*lm*dm*cm*tm

def best_segments(keys, tol=True):
    n=len(keys)
    if n<4: return None
    NEG=float('-inf')
    dp=[NEG]*(n+1); dp[0]=0.0; best=[None]*(n+1)
    for i in range(1,n+1):
        for ln in range(1,min(12,i)+1):
            j=i-ln
            if dp[j]==NEG: continue
            nd=node(keys[j:i])
            if nd and nd.ex:
                prevw=best[j][1].word if best[j] else None
                for e in nd.ex.values():
                    if not tol and e.tol: continue
                    b=boost(e.word)
                    if len(e.word)==1: ew=min(e.w+min(b,50_000),120_000)
                    else: ew=e.w+min(b,200_000)
                    lp=math.log(max(ew,1))-LOGTOTAL
                    bonus=3.5*(len(e.word)-1) if len(e.word)>1 else 0.0
                    pen=-18.0 if e.tol else 0.0
                    sc=dp[j]+lp+bonus+pen
                    if sc>dp[i]: dp[i]=sc; best[i]=(j,e)
    if dp[n]==NEG: return None
    segs=[];cur=n
    while cur>0:
        j,e=best[cur]; segs.append((e,cur-j)); cur=j
    segs.reverse(); return segs

def best_sentence(keys,tol=True):
    s=best_segments(keys,tol)
    if s and len(s)>1:
        word=''.join(e.word for e,_ in s)
        zy=' '.join(e.zy for e,_ in s)
        avg=sum(math.log(max(e.w,1)) for e,_ in s)/len(s)
        return E(word,zy,min(int(math.exp(avg)),100_000_000)), s
    return None,s

def exact_syl(keys):
    m=EXACT_KEY_MAP.get(tuple(keys),[])
    return sorted(m,key=lambda s:SYL_W.get(s,100),reverse=True)

FULL=set('ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ')
def multi_combos(keys,maxc=8):
    n=len(keys)
    if n<4 or n>6: return []
    dp=[[] for _ in range(n+1)]; dp[0]=[("",0.0)]
    for i in range(1,n+1):
        cands=[]
        for ln in range(1,min(3,i)+1):
            j=i-ln
            if not dp[j]: continue
            m=EXACT_KEY_MAP.get(tuple(keys[j:i]))
            if not m: continue
            m=sorted(m,key=lambda s:SYL_W.get(s,100),reverse=True)  # NOTE: kotlin uses insertion order (not sorted) here
            lb=(ln-1)*8.0 if ln>1 else -5.0
            for s in m[:4]:
                fb=10.0 if s not in FULL else 0.0
                ss=math.log(max(SYL_W.get(s,100),10))+lb+fb
                for pt,ps in dp[j]: cands.append((pt+s,ps+ss))
        cands.sort(key=lambda x:-x[1])
        seen=set();pr=[]
        for t,s in cands:
            if t in seen: continue
            seen.add(t);pr.append((t,s))
            if len(pr)>=8: break
        dp[i]=pr
    return [t for t,_ in dp[n]][:maxc]

def run(keys, tol=True, tone=None, topn=10, show_combos=True):
    clean=[k for k in keys if k!=11]
    L=len(clean)
    ex=search_exact(keys,tol)
    pf=search_prefix(keys,3,tol)
    pool={}
    for e in ex: pool[e.word]=(e,eff_weight(e,L,True))
    if L>=4:
        se,segs=best_sentence(clean,tol)
        if se:
            sc=se.w*2.5*2.0
            o=pool.get(se.word)
            if o is None or sc>o[1]: pool[se.word]=(se,sc)
    for p in pf:
        sc=eff_weight(p,L,False)
        o=pool.get(p.word)
        if o is None or sc>o[1]: pool[p.word]=(p,sc)
    ranked=sorted(pool.values(),key=lambda t:(t[1]>=1e9,not t[0].tol,t[1]),reverse=True)
    cl=[e for e,_ in ranked]
    combos=[]
    def add(x):
        if x not in combos: combos.append(x)
    if L<=3:
        for s in exact_syl(clean):
            add(s)
            if len(combos)>=16: break
    for e in cl:
        if e.tol: continue
        c=''.join(ch for ch in e.zy if ch not in TONES)
        pre=c[:L] if len(c)>=L else c
        if pre: add(pre)
        if len(combos)>=16: break
    if 4<=L<=6 and len(combos)<6:
        for c in multi_combos(clean,8):
            add(c)
            if len(combos)>=8: break
    if tol and len(combos)<16:
        for e in cl:
            if not e.tol: continue
            c=''.join(ch for ch in e.zy if ch not in TONES)
            pre=c[:L] if len(c)>=L else c
            if pre: add(pre)
            if len(combos)>=16: break
    return cl,combos,pool

def zk(zy): return seq(zy,False)

def valid_combo(c):
    # is the combo a sequence of valid syllables?
    from functools import lru_cache
    @lru_cache(None)
    def f(i):
        if i==len(c): return True
        for l in (3,2,1):
            if i+l<=len(c) and c[i:i+l] in VALID_SET and f(i+l): return True
        return False
    return f(0)

if __name__=='__main__':
    pass
