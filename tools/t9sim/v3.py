import math
from sim import *
import v2
from v2 import P, seg_cost, subtree_top

def suffix_best(keys, tol=True, tail_depth=1):
    """suffix[k] = 覆蓋 keys[k:] 的最小成本（可含尾端未完成詞）"""
    n=len(keys); INF=float('inf')
    suf=[INF]*(n+1); suf[n]=0.0
    for k in range(n-1,-1,-1):
        best=INF
        for ln in range(1,min(12,n-k)+1):
            if suf[k+ln]==INF: continue
            nd=node(keys[k:k+ln])
            if not nd or not nd.ex: continue
            for e in nd.ex.values():
                if e.tol and not tol: continue
                c=seg_cost(e)+(8.0 if e.tol else 0)+suf[k+ln]
                if c<best: best=c
        # 尾端未完成詞
        nd=node(keys[k:n])
        if nd:
            for w,miss,e in subtree_top(nd,tail_depth,6):
                c=seg_cost(e)+P.TAIL_MISS*miss
                if c<best: best=c
        suf[k]=best
    return suf

def partial_cands(keys, tol=True, limit=6, tail_depth=1):
    n=len(keys)
    if n<4: return []
    suf=suffix_best(keys,tol,tail_depth)
    out={}
    for k in range(1,n):
        if suf[k]==float('inf'): continue
        nd=node(keys[:k])
        if not nd: continue
        for e in nd.ex.values():
            if e.tol and not tol: continue
            c=seg_cost(e)+(8.0 if e.tol else 0)+suf[k]
            o=out.get(e.word)
            if o is None or c<o[0]: out[e.word]=(c,e,k)
    lst=sorted(out.values(),key=lambda t:t[0])
    return lst[:limit]

def run3(keys, tol=True, npart=6, tail_depth=1):
    cl,sent,_=v2.run2(keys,tol)
    clean=[k for k in keys if k!=11]
    part=[(e,k,c) for c,e,k in partial_cands(clean,tol,npart,tail_depth)] if False else [(e,k,c) for c,e,k in [(t[0],t[1],t[2]) for t in partial_cands(clean,tol,npart,tail_depth)]]
    return cl,sent,part
