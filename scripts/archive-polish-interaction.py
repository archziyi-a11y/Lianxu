import os,subprocess,time,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ADB="E:/Dev/Android/sdk/platform-tools/adb.exe"
out=ROOT/"docs/validation/archive-polish"
def adb(*args):return subprocess.run([ADB,"-s","emulator-5554",*args],check=True,capture_output=True)
adb("shell","am","start","-S","-W","-f","0x10008000","-n","com.example.lianxu.evidence/com.example.lianxu.EvidenceActivity","--es","runId",str(uuid.uuid4()),"--ez","archive","true")
time.sleep(8)
env=os.environ.copy();env.update(JAVA_HOME="E:/Program Files/Android/Android Studio/jbr",ANDROID_HOME="E:/Dev/Android/sdk",MAESTRO_CLI_NO_ANALYTICS="1",MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED="true")
with (out/"maestro.log").open("wb") as log:
 subprocess.run([str(ROOT/"app/build/workflow-tools/maestro/bin/maestro.bat"),"test","--udid","emulator-5554","--no-ansi","--test-output-dir",str(out/"maestro"),"--format","JUNIT","--output",str(out/"junit.xml"),str(ROOT/"scripts/workflow/archive-review.yaml")],stdout=log,stderr=subprocess.STDOUT,env=env,check=True,timeout=180)
