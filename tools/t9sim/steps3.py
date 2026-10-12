from sents import *
import v2, v3
from v2 import P
def avail(text, rk, npart, topn, td):
    clean=[k for k in rk if k!=11]; L=len(clean)
    cl,sent,_=v2.run2(rk)
    part=[(t[1],t[2],t[0]) for t in v3.partial_cands(clean,True,npart,td)]
    cands=[(e.word,L) for e,_ in cl[:2]]+[(e.word,L) for c,e,k,ch in sent[:2]]+[(e.word,L) for e,_ in cl[2:8]]
    # 部分候選：插在第 4 位之後（避免搶走整詞）
    ins=[(e.word,k) for e,k,c in part]
    cands=cands[:4]+ins+cands[4:]
    seen=set(); show=[]
    for w,k in cands:
        if (w,k) in seen: continue
        seen.add((w,k)); show.append((w,k))
    for pos,(w,k) in enumerate(show[:topn]):
        if text.startswith(w) and (k<L or w==text): return (w,k,pos)
    return None
def go(npart,topn,td):
    ok=tot=done=0; poss=[]
    for chunks,zys in SENTS:
        target=''.join(chunks); rk=seq(''.join(zys),True); good=True
        while target:
            r=avail(target,rk,npart,topn,td); tot+=1
            if r is None: good=False;break
            w,k,pos=r; ok+=1; poss.append(pos)
            target=target[len(w):]; rk=rk[k:] if k<=len(rk) else []
            # k 為「注音鍵數」；rk 含聲調鍵時需對齊，這裡測試用無聲調鍵
        done+=good
    avgp=sum(poss)/max(len(poss),1)
    print(f"npart={npart} 顯示前{topn} tailDepth={td}: 步驟可行 {ok}/{tot}={ok/tot:.0%} 平均位置={avgp:.1f}  整句可逐步完成 {done}/{len(SENTS)}",flush=True)
for a in [(6,10,1),(8,12,1),(12,14,1)]: go(*a)
