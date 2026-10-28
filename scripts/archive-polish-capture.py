import subprocess,time,pathlib,uuid,argparse
parser=argparse.ArgumentParser()
parser.add_argument("--output",default="docs/validation/archive-polish")
args=parser.parse_args()
A="E:/Dev/Android/sdk/platform-tools/adb.exe";out=pathlib.Path(args.output);out.mkdir(parents=True,exist_ok=True)
def a(*x):return subprocess.check_output([A,*x])
a("install","-r","app/build/outputs/apk/evidence/app-evidence.apk")
for sparse in [False,True]:
 for dark in [False,True]:
  name=("sparse" if sparse else "rich")+("-dark" if dark else "-light")
  a("shell","am","start","-S","-W","-f","0x10008000","-n","com.example.lianxu.evidence/com.example.lianxu.EvidenceActivity","--es","runId","archive-"+name+"-"+uuid.uuid4().hex[:6],"--ez","archive","true","--ez","sparse",str(sparse).lower(),"--ez","dark",str(dark).lower())
  time.sleep(8);(out/("after-"+name+".png")).write_bytes(a("exec-out","screencap","-p"))
  if not sparse:
   for i in range(3):
    a("shell","input","swipe","980","1930","980","650","450");time.sleep(1)
    (out/("after-"+name+"-scroll-"+str(i)+".png")).write_bytes(a("exec-out","screencap","-p"))
print(out)
