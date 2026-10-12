from sim import *
RAW = """今天|天氣|很|好
我|想|吃|晚餐
你|在|做|什麼
明天|要|去|上班
謝謝|你|的|幫忙
請問|廁所|在|哪裡
我們|一起|去|看|電影
這個|週末|有|空|嗎
他|最近|工作|很|忙
可以|幫我|買|咖啡|嗎
我|不|知道|怎麼|辦
等一下|我|打給|你
現在|幾點
早安|今天|也|要|加油
你|吃飯|了|嗎
我|喜歡|台灣|的|夜市
晚上|一起|吃|火鍋
這裡|的|東西|很|好吃
對不起|我|遲到|了
沒問題|交給|我
手機|沒|電|了
我|要|去|圖書館|借書
老師|說|明天|考試
下午|開會|記得|帶|電腦
幫|我|訂|飯店
捷運|站|在|前面
我|覺得|這樣|不|太|好
你|可以|再|說|一次|嗎
週末|去|爬山|好不好
他|已經|回家|了
我們|公司|在|台北
請|稍等|一下
大家|辛苦|了
祝你|生日|快樂
最近|天氣|變冷|了
我|想|喝|珍珠|奶茶
明天|下雨|記得|帶|傘
他|不|喜歡|吃|辣
你好|請問|有|什麼|需要|幫忙
今天|晚上|有|空|嗎
我|在|路上|馬上|到
這|是|我|的|榮幸
謝謝|大家
工作|很|累|想|休息
明天|早上|九點|開會
你|最近|過|得|好嗎
我們|約|在|車站|見面
不好意思|打擾|了
可以|給|我|你的|電話|嗎""".split('\n')
ENT={}
for line in open(DICT,encoding='utf-8'):
    p=line.rstrip('\n').split('\t')
    if len(p)<3: continue
    try: w=int(p[2])
    except: continue
    o=ENT.get(p[0])
    if o is None or w>o[1]: ENT[p[0]]=(p[1],w)
SENTS=[]
for r in RAW:
    chunks=r.split('|'); zys=[];ok=True
    for c in chunks:
        if c in ENT: zys.append(ENT[c][0])
        else:
            for ch in c:
                if ch in ENT: zys.append(ENT[ch][0])
                else: ok=False
    if ok: SENTS.append((chunks,zys))
if __name__=='__main__':
    print(len(SENTS),"sentences")
    for c,z in SENTS[:5]: print(c,z)
