"""Check source fidelity, references, computed meals, and representative target fit."""
from decimal import Decimal
import hashlib
import itertools
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
D=ROOT/'assets'/'food'

def read(n): return json.loads((D/n).read_text(encoding='utf-8'))
def main():
    manifest=read('manifest.json')
    for n,h in manifest['outputs'].items():
        assert hashlib.sha256((D/n).read_bytes()).hexdigest()==h,n
    foods=read('foods.v1.json')['foods']; recipes=read('recipes.v1.json')['recipes']
    ids={f['id'] for f in foods}; assert len(ids)==len(foods)
    byid={f['id']:f for f in foods}
    sources={r['fdcId']:r for r in read('source-records.v1.json')['records']}
    keys=['energyKcal','proteinG','carbohydrateG','fatG','fiberG']
    fidelity=0
    for f in foods:
        raw={r['nutrient_id']:r['amount'] for r in sources[f['sourceId']]['nutrients']}
        assert f['nameOriginal']==sources[f['sourceId']]['food']['description']
        for nid,k in manifest['nutrientIds'].items():
            assert Decimal(str(f['nutrients'][k]))==Decimal(raw[nid]),(f['id'],k)
            assert f['nutrients'][k]>=0
            fidelity+=1
    assert len({r['id'] for r in recipes})==len(recipes)
    for r in recipes:
        assert all(i['foodId'] in ids and i['grams']>0 for i in r['ingredients'])
        for k in keys:
            total=sum(Decimal(str(byid[i['foodId']]['nutrients'][k]))*Decimal(str(i['grams']))/100 for i in r['ingredients'])
            assert abs(total-Decimal(str(r['nutrientsPerBaseServing'][k])))<Decimal('0.000001')
    results=[]
    for goal,energy,protein in [('gain',2901.8,126),('cut',2242.3,140)]:
        fat=energy*.25/9; carb=(energy-protein*4-fat*9)/4
        target=dict(zip(keys,[energy,protein,carb,fat,energy*.014]))
        groups=[]
        for slot in ['breakfast','lunch','dinner']:
            candidates=[]
            for r in recipes:
                if r['scene']!='home' or slot not in r['mealKeys']:continue
                for scale in [.75,1,1.25]:
                    # Round actual ingredient portions, then recalculate, as the spec requires.
                    ingredients=[{**i,'grams':int(i['grams']*scale/5+.5)*5} for i in r['ingredients']]
                    totals={k:sum(byid[i['foodId']]['nutrients'][k]*i['grams']/100 for i in ingredients) for k in keys}
                    candidates.append((r['id'],scale,ingredients,totals))
            groups.append(candidates)
        best=None
        for combo in itertools.product(*groups):
            total={k:sum(c[3][k] for c in combo) for k in keys}
            score=sum(w*abs(total[k]-target[k])/max(target[k],1) for k,w in zip(keys[:4],[3,3,1,1]))+max(0,target['fiberG']-total['fiberG'])/max(target['fiberG'],1)
            tie=tuple((c[0],c[1]) for c in combo)
            if best is None or (score,tie)<(best[0],best[1]): best=(score,tie,total,combo)
        score,tie,total,combo=best
        e_error=abs(total['energyKcal']-energy)/energy
        p_error=abs(total['proteinG']-protein)/protein
        assert e_error<=.10 and p_error<=.15,(goal,e_error,p_error)
        results.append(dict(goal=goal,target=target,actual=total,energyRelativeError=e_error,proteinRelativeError=p_error,
                            meals=[dict(recipeId=c[0],scale=c[1],ingredients=c[2],totals=c[3]) for c in combo]))
    report=dict(status='passed',sourceNutrientComparisons=fidelity,foods=len(foods),recipes=len(recipes),
                checks=['manifest hashes','source names and nutrient equality','foreign keys','unique ids','recipe totals','rounded serving target fit'],
                illustrativePerson='synthetic 70kg 175cm age30 male factor1.6; not user data',targetFitExamples=results)
    (D/'validation-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ['targetFitExamples']},ensure_ascii=False))

if __name__=='__main__':main()
