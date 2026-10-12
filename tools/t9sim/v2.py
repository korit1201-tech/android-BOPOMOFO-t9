import heapq, math
from sim import *

def subtree_top(nd, maxd=3, k=8):
    """子樹中(深度<=maxd)權重最高的 k 個詞及其額外鍵數"""
    out=[]
    def rec(n,d):
        if d>maxd: return
        for c in n.ch.values():
            for e in c.ex.values():
                if not e.tol: out.append((e.w,d+1,e))
            rec(c,d+1)
    rec(nd,0)
    out.sort(key=lambda t:-t[0])
    return out[:k]

class P:  # 參數
    WORD_PEN=3.5      # 每個詞的固定成本（偏好少詞）
    MIN_W=0           # DP 中多字詞最低權重（過濾冷僻詞）
    SINGLE_CAP=False  # 是否沿用單字權重截斷
    TAIL_MISS=2.5     # 尾端未打完，每缺一鍵成本
    BEAM=4
    TAIL=True

def seg_cost(e, ):
    W=e.w
    b=boost(e.word)
    if len(e.word)==1:
        W=W+min(b,50_000)
        if P.SINGLE_CAP: W=min(W,120_000)
    else:
        W=W+min(b,200_000)
    return -math.log(max(W,1))+LOGTOTAL + P.WORD_PEN

def kbest(keys, tol=True):
    n=len(keys)
    # hyp[i] = list of (cost, prev_i, prev_rank, entry, consumed_end_flag)
    hyp=[[] for _ in range(n+1)]
    hyp[0]=[(0.0,-1,-1,None)]
    for i in range(1,n+1):
        cand=[]
        for ln in range(1,min(12,i)+1):
            j=i-ln
            if not hyp[j]: continue
            nd=node(keys[j:i])
            if not nd or not nd.ex: continue
            for e in nd.ex.values():
                if e.tol and not tol: continue
                if len(e.word)>1 and e.w<P.MIN_W: continue
                c=seg_cost(e)+(8.0 if e.tol else 0)
                for r,h in enumerate(hyp[j]):
                    cand.append((h[0]+c,j,r,e))
        cand.sort(key=lambda t:t[0])
        seen=set(); keep=[]
        for t in cand:
            # 以(路徑文字)去重
            key=(t[3].word,t[1],t[2])
            if key in seen: continue
            seen.add(key); keep.append(t)
            if len(keep)>=P.BEAM*3: break
        # 依整串文字去重
        txt=set(); final=[]
        for t in keep:
            s=path_text(hyp,t)
            if s in txt: continue
            txt.add(s); final.append(t)
            if len(final)>=P.BEAM: break
        hyp[i]=final
    return hyp

def path_text(hyp,t):
    words=[]
    while t and t[3] is not None:
        words.append(t[3].word); 
        j,r=t[1],t[2]
        t=hyp[j][r] if j>=0 and hyp[j] else None
    return ''.join(reversed(words))

def path_entries(hyp,t):
    es=[]
    while t and t[3] is not None:
        es.append(t[3]); j,r=t[1],t[2]
        t=hyp[j][r] if j>=0 else None
    es.reverse(); return es

def segs_of(hyp,t,keys):
    # returns [(entry,keylen)]
    out=[]; i=len(keys)
    # reconstruct with positions
    chain=[]
    cur=t; end=None
    return None

def kbest_paths(keys,tol=True):
    """回傳完整(全部鍵被詞覆蓋)的前 BEAM 條路徑: (cost,[(entry,keylen)...])"""
    n=len(keys)
    hyp=[[] for _ in range(n+1)]
    hyp[0]=[(0.0,None)]  # (cost, chain) chain=list of (entry,len)
    for i in range(1,n+1):
        cand=[]
        for ln in range(1,min(12,i)+1):
            j=i-ln
            if not hyp[j]: continue
            nd=node(keys[j:i])
            if not nd or not nd.ex: continue
            for e in nd.ex.values():
                if e.tol and not tol: continue
                if len(e.word)>1 and e.w<P.MIN_W: continue
                c=seg_cost(e)+(8.0 if e.tol else 0)
                for h in hyp[j]:
                    cand.append((h[0]+c,(h[1] or [])+[(e,ln)]))
        cand.sort(key=lambda t:t[0])
        txt=set(); keep=[]
        for c,ch in cand:
            s=''.join(e.word for e,_ in ch)
            if s in txt: continue
            txt.add(s); keep.append((c,ch))
            if len(keep)>=P.BEAM: break
        hyp[i]=keep
    return hyp

def tail_paths(hyp,keys):
    """尾端允許『未打完的詞』：從 hyp[j] 接一個前綴詞(含 miss 缺鍵)"""
    n=len(keys); res=[]
    for j in range(0,n):
        if not hyp[j]: continue
        nd=node(keys[j:n])
        if not nd: continue
        for w,miss,e in subtree_top(nd,3,6):
            c=seg_cost(e)+P.TAIL_MISS*miss
            for h in hyp[j]:
                res.append((h[0]+c,(h[1] or [])+[(e,n-j)],miss))
    return res

def run2(keys, tol=True, partial=True):
    clean=[k for k in keys if k!=11]; L=len(clean)
    ex=search_exact(keys,tol)
    pf=search_prefix(keys,3,tol)
    pool={}
    for e in ex: pool[e.word]=(e,eff_weight(e,L,True),L)
    for p_ in pf:
        sc=eff_weight(p_,L,False)
        o=pool.get(p_.word)
        if o is None or sc>o[1]: pool[p_.word]=(p_,sc,L)
    ranked=sorted(pool.values(),key=lambda t:(t[1]>=1e9,not t[0].tol,t[1]),reverse=True)
    cl=[(e,L) for e,_,_ in ranked]
    sent=[]; part=[]
    if L>=4:
        hyp=kbest_paths(clean,tol)
        full=hyp[L]
        # 整句：多詞路徑
        top_single_cost=None
        for c,ch in full:
            if len(ch)<2: continue
            word=''.join(e.word for e,_ in ch)
            zy=' '.join(e.zy for e,_ in ch)
            sent.append((c,E(word,zy,1),L,ch))
        tails=tail_paths(hyp,clean) if P.TAIL else []
        tails.sort(key=lambda t:t[0])
        # 部分上屏候選：取最佳路徑們的『第一個詞』(消耗 k<L 鍵)
        seenp=set()
        allp=[(c,ch) for c,ch in full]+[(c,ch) for c,ch,_ in tails]
        allp.sort(key=lambda t:t[0])
        for c,ch in allp:
            if len(ch)<2: continue
            e,k=ch[0]
            if k>=L: continue
            if e.word in seenp: continue
            seenp.add(e.word); part.append((e,k,c))
            if len(part)>=6: break
    return cl,sent,part

def to_list(cl,sent,part,mode="append"):
    """合併成候選列"""
    words=[]; 
    seen=set()
    def add(w,k):
        if w in seen: return
        seen.add(w); words.append((w,k))
    # 先放整句（最佳一條）到合理位置：在現有 exact/prefix 前三名之後
    head=cl[:2]; rest=cl[2:]
    for e,k in head: add(e.word,k)
    for c,e,k,ch in sent[:2]: add(e.word,k)
    for e,k in rest[:6]: add(e.word,k)
    for e,k,c in part[:4]: add(e.word,k)
    for e,k in rest[6:]: add(e.word,k)
    return words
