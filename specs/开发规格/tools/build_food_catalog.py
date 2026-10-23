"""Build a small, attributed food asset from the official USDA SR Legacy archive.
Uses only Python standard library. No secrets or API calls.
"""
import csv
import hashlib
import io
import itertools
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'assets' / 'food'
ARCHIVE = DEST / 'sources' / 'sr-legacy-2018-04.zip'
SOURCE_URL = 'https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip'
# Translations describe the actual selected source food, not arbitrary brand equivalence.
SELECT = [
 ('168878','熟长粒白米饭（强化）','cooked','grain',[]),
 ('169756','生长粒白米（未强化）','raw','grain',[]),
 ('173904','干燕麦片（未强化）','raw','grain',['oats']),
 ('173905','水煮燕麦粥（无盐）','cooked','grain',['oats']),
 ('172688','全麦面包（商业配方）','as_sold','grain',['wheat']),
 ('171477','烤鸡胸肉（仅肉）','cooked','protein',[]),
 ('173424','水煮全蛋','cooked','protein',['egg']),
 ('172475','硬豆腐（硫酸钙凝固）','raw','protein',['soy']),
 ('171267','2%脂肪牛奶（强化维生素）','as_sold','dairy',['milk']),
 ('170894','原味脱脂希腊酸奶','as_sold','dairy',['milk']),
 ('170903','原味低脂希腊酸奶','as_sold','dairy',['milk']),
 ('173944','香蕉（生，可食部）','raw','fruit',[]),
 ('171688','带皮苹果（生，可食部）','raw','fruit',[]),
 ('169967','水煮西兰花（沥干，无盐）','cooked','vegetable',[]),
 ('168463','水煮菠菜（沥干，无盐）','cooked','vegetable',[]),
 ('168483','烤红薯（薯肉，无盐）','cooked','starch',[]),
 ('170440','去皮水煮土豆（无盐）','cooked','starch',[]),
 ('171413','橄榄油','as_sold','fat',[]),
 ('175168','熟养殖大西洋三文鱼（干热）','cooked','protein',['fish']),
 ('171791','熟95%瘦牛肉饼（烤制）','cooked','protein',[]),
 ('168250','烤猪里脊（仅瘦肉）','cooked','protein',[]),
 ('172421','水煮扁豆（无盐）','cooked','legume',[]),
 ('173735','水煮黑豆（无盐）','cooked','legume',[]),
 ('173806','干烤花生（无盐）','as_sold','fat',['peanut']),
 ('170393','胡萝卜（生）','raw','vegetable',[]),
 ('168409','带皮黄瓜（生）','raw','vegetable',[]),
 ('170457','红番茄（生）','raw','vegetable',[]),
 ('169999','水煮甜玉米（沥干，无盐）','cooked','starch',[]),
 ('169283','水煮青大豆（沥干，无盐）','cooked','legume',['soy']),
 ('169252','水煮白蘑菇（沥干，无盐）','cooked','vegetable',[]),
 ('169762','熟鸡蛋面（未强化，加盐）','cooked','grain',['wheat','egg']),
]
NUTRIENTS = {'1008':'energyKcal','1003':'proteinG','1005':'carbohydrateG','1004':'fatG','1079':'fiberG'}

def dump(name, value):
    (DEST / name).write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

def main():
    z = zipfile.ZipFile(ARCHIVE)
    def rows(name):
        member = next(n for n in z.namelist() if n.endswith('/'+name))
        return csv.DictReader(io.TextIOWrapper(z.open(member), encoding='utf-8-sig'))
    source_foods = {r['fdc_id']:r for r in rows('food.csv')}
    selected_ids = {s[0] for s in SELECT}
    raw_nutrients = {fid:[] for fid in selected_ids}
    for row in rows('food_nutrient.csv'):
        if row['fdc_id'] in selected_ids and row['nutrient_id'] in NUTRIENTS:
            raw_nutrients[row['fdc_id']].append(row)
    raw_portions = {fid:[] for fid in selected_ids}
    for row in rows('food_portion.csv'):
        if row['fdc_id'] in selected_ids:
            raw_portions[row['fdc_id']].append(row)
    definitions = [r for r in rows('nutrient.csv') if r['id'] in NUTRIENTS]
    foods = []
    for fid, name, state, category, allergens in SELECT:
        source = source_foods[fid]
        values = {v:None for v in NUTRIENTS.values()}
        for r in raw_nutrients[fid]:
            key = NUTRIENTS[r['nutrient_id']]
            assert values[key] is None, (fid,key,'duplicate')
            values[key] = float(r['amount']) if r['amount'] else None
        assert all(values[k] is not None for k in ['energyKcal','proteinG','carbohydrateG','fatG'])
        foods.append(dict(id='usda-sr-'+fid, sourceName='USDA FoodData Central / SR Legacy',
            sourceId=fid,sourceVersion='SR Legacy final 2018-04',
            sourceUrl=f'https://fdc.nal.usda.gov/food-details/{fid}/nutrients',
            retrievedAt='2026-10-23',nameZh=name,nameOriginal=source['description'],
            aliases=[name.split('（')[0]],state=state,category=category,
            ingredients=[name.split('（')[0]],allergens=allergens,
            allergenStatus='unknown' if category=='grain' and fid=='172688' else 'known',
            allergenNote='按食物身份标注，不代表具体商品交叉接触或完整配料认证。',
            basisG=100,nutrients=values,carbohydrateBasis='total_including_fiber',
            portions=[dict(label='100克可食部',grams=100,origin='database',sourceRef=f'USDA FDC {fid}; 100g nutrient basis')],
            origin='database',sourceSnapshot={'food':source,'nutrients':raw_nutrients[fid],'portions':raw_portions[fid]}))
    # These are authored meal suggestions built from sourced ingredients, not measured restaurant dishes.
    defs = [
      ('oats-milk-banana','燕麦牛奶香蕉',['breakfast'],[('173904',100),('171267',250),('173944',150),('173806',15)]),
      ('bread-egg-yogurt','全麦面包鸡蛋酸奶',['breakfast'],[('172688',120),('173424',100),('170894',200),('171688',150)]),
      ('sweetpotato-egg-milk','红薯鸡蛋牛奶',['breakfast'],[('168483',300),('173424',100),('171267',250),('173944',100)]),
      ('yogurt-oats-fruit','酸奶燕麦水果',['breakfast'],[('170894',250),('173904',100),('173944',150),('173806',20)]),
      ('rice-chicken-broccoli','米饭鸡胸西兰花',['lunch','dinner'],[('168878',400),('171477',100),('169967',150),('171413',10)]),
      ('rice-tofu-spinach','米饭豆腐菠菜',['lunch','dinner'],[('168878',450),('172475',150),('168463',150),('171413',10)]),
      ('rice-salmon-vegetable','米饭三文鱼蔬菜',['lunch','dinner'],[('168878',350),('175168',150),('169967',150)]),
      ('potato-pork-vegetable','土豆里脊蔬菜',['lunch','dinner'],[('170440',450),('168250',150),('169967',150),('171413',10)]),
      ('rice-beans-egg','米饭黑豆鸡蛋',['lunch','dinner'],[('168878',300),('173735',150),('173424',100),('170457',150)]),
      ('noodles-beef-vegetable','鸡蛋面牛肉蔬菜',['lunch','dinner'],[('169762',350),('171791',150),('169252',150),('171413',5)]),
      ('rice-chicken-corn','米饭鸡胸玉米',['lunch','dinner'],[('168878',350),('171477',100),('169999',200),('168409',150),('171413',10)]),
      ('rice-lentils-tofu','米饭扁豆豆腐',['lunch','dinner'],[('168878',300),('172421',150),('172475',100),('170393',150),('171413',5)]),
    ]
    recipes=[]
    byid={f['id']:f for f in foods}
    for key,name,slots,items in defs:
        for scene in ['home','canteen','takeout']:
            ing=[dict(foodId='usda-sr-'+fid,grams=g) for fid,g in items]
            total={n:round(sum(byid[i['foodId']]['nutrients'][n]*i['grams']/100 for i in ing),6)
                   if all(byid[i['foodId']]['nutrients'][n] is not None for i in ing) else None for n in NUTRIENTS.values()}
            recipes.append(dict(id=f'recipe-{key}-{scene}',revision=1,name=name,scene=scene,mealKeys=slots,
                ingredients=ing,baseServings=1,portionMinFactor=.75,portionMaxFactor=1.25,
                excludedTags=[],preparation='按所列食物生熟状态和可食重量组合；调味和额外用油另计。',
                origin='authored_from_sourced_foods',nutrientsPerBaseServing=total,
                sceneNote='建议组合；食堂/外卖实际份量和烹调须按需要估计，不是具体商家实测营养。'))
    dump('foods.v1.json',{'schemaVersion':1,'foods':foods})
    dump('recipes.v1.json',{'schemaVersion':1,'recipes':recipes})
    dump('source-records.v1.json',{'nutrientDefinitions':definitions,'records':[
        {'fdcId':f['sourceId'],**f['sourceSnapshot']} for f in foods]})
    manifest=dict(schemaVersion=1,catalogVersion='pilot-2026-10-23',retrievedAt='2026-10-23',
        sourceUrl=SOURCE_URL,archiveSha256=hashlib.sha256(ARCHIVE.read_bytes()).hexdigest(),
        archiveBytes=ARCHIVE.stat().st_size,license='CC0; USDA FoodData Central public-domain data',
        foodCount=len(foods),recipeCount=len(recipes),nutrientIds=NUTRIENTS,
        note='SR Legacy final dataset 2018-04; downloaded food.csv publication_date may be 2019-04-01. Translations and meal combinations authored for this app.',
        outputs={n:hashlib.sha256((DEST/n).read_bytes()).hexdigest() for n in ['foods.v1.json','recipes.v1.json','source-records.v1.json']})
    dump('manifest.json',manifest)
    print(json.dumps({'foods':len(foods),'recipes':len(recipes),'missingFiber':[f['sourceId'] for f in foods if f['nutrients']['fiberG'] is None]},ensure_ascii=False))

if __name__ == '__main__': main()
