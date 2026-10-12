import math
from sents import *
import v2
from v2 import P, run2, kbest_paths, seg_cost, subtree_top
import bench2
from bench2 import setA,setB

def base_cost(e,L,exact):
    tk=len(seq(e.zy,True)); miss=max(0,tk-L)
    return seg_cost(e)+(0 if exact else P.TAIL_MISS*miss)

def merged(keys, mode):
    clean=[k for k in keys if k!=11]; L=len(clean)
    if mode=="old":
        cl,_,_=run(keys); return [(e.word,L,' ' in e.zy) for e in cl]
    cl,sent,part=run2(keys)
    base=[(e.word,L,False,base_cost(e,L,len(seq(e.zy,True))<=L)) for e,_ in cl]
    out=list(base)
    if mode in("v2","v2p"):
        ins=[(c,e.word,k,True) for c,e,k,ch in sent[:2]]
        for c,w,k,f in reversed(ins):
            pos=0
            while pos<len(out) and out[pos][3]<=c: pos+=1
            out.insert(pos,(w,k,True,c))
        if mode=="v2p":
            for e,k,c in part[:3]:
                pos=0
                while pos<len(out) and out[pos][3]<=c+2: pos+=1
                out.insert(min(pos+1,len(out)),(e.word,k,False,c))
    return [(w,k,f) for w,k,f,*_ in out]

def run_mid(mode):
    tot=top1=top3=top5=comp1=0; seen=set()
    for x,zy in setA[:120]+setB[:120]:
        ks=zk(zy)
        for L in range(3,len(ks)):
            m=merged(ks[:L],mode)
            ws=[w for w,k,f in m]
            # 目標：candidate 以 x 或 x 的前綴詞為準；打到一半時 x 應出現（前綴預測）
            tot+=1
            top1+= ws[:1]==[x]; top3+= x in ws[:3]; top5+= x in ws[:5]
            if m and m[0][2] and m[0][0]!=x: comp1+=1
    print(f"[{mode:4s}] 打到一半狀態 N={tot}  目標在 top1={top1/tot:5.1%} top3={top3/tot:5.1%} top5={top5/tot:5.1%}  整句composite佔第一={comp1/tot:5.1%}")
for mode in ("old","base","v2","v2p"): run_mid(mode)
