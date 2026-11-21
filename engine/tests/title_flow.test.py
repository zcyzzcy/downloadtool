from pathlib import Path
import subprocess,shutil,tempfile
s=Path('engine/app/src/main/java/com/palworld/vd/LocalEngine.java').read_text(encoding='utf-8')
a=s.index('        String clean = outTitle');b=s.index('        final String id =',a)
start=s.index('    private static String sanitizeName(');end=s.index('\n    }',start)+6
java='''class TitleTest {'''+s[start:end]+'''\nstatic String[] title(String outTitle,int force){'''+s[a:b]+'''return new String[]{fBase,hint};}
public static void main(String[] args){
 for(String empty:new String[]{null,"","  "})for(int force:new int[]{0,1}){
  String[] result=title(empty,force);if(!result[0].isEmpty()||!result[1].isEmpty())throw new AssertionError("empty title became download");
 }
 if(!title("Real title",0)[1].equals("Real title"))throw new AssertionError();
 if(!title("Real title",1)[0].equals("Real title"))throw new AssertionError();
 if(!title("Real title",0)[0].isEmpty())throw new AssertionError();
}}
'''
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'TitleTest.java';p.write_text(java,encoding='utf-8')
 subprocess.run([shutil.which('javac'),'-J-Xmx128m',str(p)],check=True)
 subprocess.run([shutil.which('java'),'-Xmx128m','-cp',d,'TitleTest'],check=True)
print('Empty title, real title and forced-name preservation passed')
