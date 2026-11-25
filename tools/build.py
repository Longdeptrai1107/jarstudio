"""Build a runnable JAR and a self-contained Windows EXE application image."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import zipfile

ROOT=Path(__file__).resolve().parents[1]


def java_home():
    candidates=[]
    if os.environ.get('JAVA_HOME'):candidates.append(Path(os.environ['JAVA_HOME']))
    recaf=Path(os.environ.get('LOCALAPPDATA',str(Path.home()/'AppData/Local')))/'Programs/RecafRuntime'
    if recaf.exists():candidates.extend(sorted(recaf.glob('jdk-*'),reverse=True))
    candidates.append(Path(r'D:\NRO\source\toolchain\java'))
    for candidate in candidates:
        if (candidate/'bin/javac.exe').is_file():return candidate
    raise RuntimeError('JDK not found. Set JAVA_HOME to JDK 25.')


def invoke(command):
    result=subprocess.run([str(item) for item in command],cwd=ROOT,capture_output=True,text=True,encoding='utf8',errors='replace')
    if result.returncode:raise RuntimeError(result.stdout+'\n'+result.stderr)
    return result.stdout


def remove_tree(directory):
    for attempt in range(60):
        try:
            shutil.rmtree(directory);return
        except PermissionError:
            time.sleep(1.0)
    # Antivirus scanners can hold the freshly written JarStudio.exe for a
    # long time. Wait patiently for that one file, then remove the rest.
    exe=directory/'JarStudio.exe'
    if exe.exists():
        for attempt in range(120):
            try:
                exe.unlink();break
            except PermissionError:
                time.sleep(2.0)
    shutil.rmtree(directory)


def prepare_resources(home):
    resources=ROOT/'build/resources';tools=resources/'tooling';tools.mkdir(parents=True,exist_ok=True)
    lock=json.loads((ROOT/'tools/dependencies.lock.json').read_text(encoding='utf8'))
    for name in ['cfr','vineflower','ecj','microemulator','cldcapi11','midpapi20']:
        row=next(item for item in lock['packages'] if item['name']==name);file=ROOT/'lib'/row['filename']
        if hashlib.sha256(file.read_bytes()).hexdigest()!=row['sha256']:raise ValueError('Dependency hash differs: '+name)
        shutil.copy2(file,tools/(name+'.jar'))
    licenses=resources/'META-INF/licenses';licenses.mkdir(parents=True,exist_ok=True)
    for row in lock['packages']:
        with zipfile.ZipFile(ROOT/'lib'/row['filename']) as archive:
            for name in archive.namelist():
                base=name.rsplit('/',1)[-1]
                if not name.endswith('/') and not name.endswith('.class') and base.upper().startswith(('LICENSE','NOTICE','COPYING')):
                    target_notice=licenses/(row['name']+'-'+row['version'])/base;target_notice.parent.mkdir(parents=True,exist_ok=True);target_notice.write_bytes(archive.read(name))
    shutil.copy2(ROOT/'tools/dependencies.lock.json',resources/'META-INF/dependencies.lock.json')
    legacy=Path(r'D:\NRO\source\toolchain\java\jmods\java.base.jmod')
    if not legacy.exists():legacy=home/'jmods/java.base.jmod'
    target=tools/'legacy-java-base.jar'
    if not target.exists():
        with zipfile.ZipFile(legacy) as source,zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as output:
            for name in source.namelist():
                if name.startswith('classes/') and name.endswith('.class') and name!='classes/module-info.class':output.writestr(name[len('classes/'):],source.read(name))
    jdk_legal=legacy.parent.parent/'legal/java.base'
    if jdk_legal.exists():shutil.copytree(jdk_legal,licenses/'OpenJDK17-java.base',dirs_exist_ok=True)
    source=ROOT/'src/main/resources'
    if source.exists():shutil.copytree(source,resources,dirs_exist_ok=True)
    helper_sources=sorted((ROOT/'src/tooling/java').rglob('*.java'))
    if helper_sources:
        helper_classes=ROOT/'build/tooling-classes';helper_classes.mkdir(parents=True,exist_ok=True)
        argument_file=ROOT/'build/tooling-sources.args';argument_file.write_text('\n'.join('"'+str(path).replace('\\','/')+'"' for path in helper_sources),encoding='utf8')
        invoke([home/'bin/javac.exe','--release','17','-encoding','UTF-8','-cp',tools/'microemulator.jar','-d',helper_classes,'@'+str(argument_file)])
        with zipfile.ZipFile(tools/'midlet-launcher.jar','w',zipfile.ZIP_DEFLATED) as output:
            for file in helper_classes.rglob('*.class'):output.write(file,file.relative_to(helper_classes).as_posix())
    return resources,lock


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--exe',action='store_true');parser.add_argument('--tests',action='store_true');parser.add_argument('--runtime-jdk',type=Path);args=parser.parse_args()
    home=java_home();build=ROOT/'build';classes=build/'classes'
    if classes.exists():shutil.rmtree(classes)
    classes.mkdir(parents=True,exist_ok=True)
    resources,lock=prepare_resources(home)
    libraries=[ROOT/'lib'/row['filename'] for row in lock['packages'] if row['name'] in {'asm','asm-tree','asm-util','asm-analysis','rsyntaxtextarea'}]
    cp=os.pathsep.join(str(path) for path in libraries)
    sources=sorted((ROOT/'src/main/java').rglob('*.java'))
    argument_file=build/'sources.args';argument_file.write_text('\n'.join('"'+str(path).replace('\\','/')+'"' for path in sources),encoding='utf8')
    print(invoke([home/'bin/javac.exe','--release','17','-encoding','UTF-8','-g','--add-modules','jdk.jdi','-cp',cp,'-d',classes,'@'+str(argument_file)]),end='')
    dist=ROOT/'dist';dist.mkdir(exist_ok=True);jar=dist/'JarStudio.jar'
    content={};services={}
    for library in libraries:
        with zipfile.ZipFile(library) as source:
            for name in source.namelist():
                if name.endswith('/') or name.endswith('module-info.class') or name.upper()=='META-INF/MANIFEST.MF' or name.upper().endswith(('.SF','.RSA','.DSA','.EC')):continue
                if name.startswith('META-INF/services/'):
                    services.setdefault(name,set()).update(source.read(name).decode('utf8').splitlines());continue
                content.setdefault(name,source.read(name))
    for name,lines in services.items():content[name]=('\n'.join(sorted(lines))+'\n').encode('utf8')
    for directory in [classes,resources]:
        for path in directory.rglob('*'):
            if path.is_file():content[path.relative_to(directory).as_posix()]=path.read_bytes()
    content['META-INF/MANIFEST.MF']=b'Manifest-Version: 1.0\r\nMain-Class: dev.jarstudio.Main\r\nMulti-Release: true\r\nImplementation-Version: 1.1.0\r\n\r\n'
    with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as output:
        for name,data in sorted(content.items()):
            info=zipfile.ZipInfo(name,date_time=(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;output.writestr(info,data)
    with zipfile.ZipFile(jar) as output:
        if output.testzip():raise ValueError('Built JAR failed CRC check')
    print('Built',jar,jar.stat().st_size,'bytes',flush=True)
    (build/'java-home.txt').write_text(str(home),encoding='utf8')
    if args.tests:
        tests=sorted((ROOT/'src/test/java').rglob('*.java'));test_classes=build/'test-classes';test_classes.mkdir(exist_ok=True)
        if tests:
            args_file=build/'test-sources.args';args_file.write_text('\n'.join('"'+str(path).replace('\\','/')+'"' for path in tests),encoding='utf8')
            print(invoke([home/'bin/javac.exe','--release','17','-encoding','UTF-8','-g','--add-modules','jdk.jdi','-cp',jar,'-d',test_classes,'@'+str(args_file)]),end='')
    if args.exe:
        runtime_home=args.runtime_jdk
        if runtime_home is None:
            configured=os.environ.get('JARSTUDIO_RUNTIME_JDK')
            compatible=Path(r'D:\NRO\source\toolchain\java')
            runtime_home=Path(configured) if configured else compatible if (compatible/'bin/jlink.exe').is_file() else home
        if not (runtime_home/'bin/jlink.exe').is_file():raise RuntimeError('Runtime JDK must include jlink.exe: '+str(runtime_home))
        runtime_id=hashlib.sha256((runtime_home/'release').read_bytes()).hexdigest()[:12]
        runtime=build/('runtime-'+runtime_id)
        if not (runtime/'bin/java.exe').exists():
            invoke([runtime_home/'bin/jlink.exe','--add-modules','java.se,jdk.compiler,jdk.jdi,jdk.jdwp.agent,jdk.attach,jdk.unsupported,jdk.zipfs','--strip-debug','--no-header-files','--no-man-pages','--compress','2','--output',runtime])
        # javac --release requires the JDK symbol archive, which jlink omits.
        shutil.copy2(runtime_home/'lib/ct.sym',runtime/'lib/ct.sym')
        windows=dist/'windows';windows.mkdir(exist_ok=True)
        existing=windows/'JarStudio'
        if os.name=='nt':
            # A running instance locks JarStudio.exe on Windows.
            subprocess.run(['taskkill','/IM','JarStudio.exe','/F'],capture_output=True)
        if existing.exists():
            # A valid image has app/JarStudio.jar. A partial rebuild may leave only
            # the jpackage app/runtime folders plus this project's own documents.
            leftover={item.name for item in existing.iterdir()}
            known={'app','runtime','README.md','FEATURES.md','THIRD_PARTY_NOTICES.md','licenses','dependencies.lock.json','JarStudio.exe'}
            valid=(existing/'app/JarStudio.jar').is_file() or leftover <= known
            if not valid:raise RuntimeError('Unexpected app image target; inspect before replacing it: %s'%', '.join(sorted(leftover)))
            remove_tree(existing)
        package_input=build/'package-input';package_input.mkdir(exist_ok=True);shutil.copy2(jar,package_input/'JarStudio.jar')
        invoke([home/'bin/jpackage.exe','--type','app-image','--name','JarStudio','--app-version','1.1.0','--vendor','JarStudio','--input',package_input,'--main-jar','JarStudio.jar','--main-class','dev.jarstudio.Main','--dest',windows,'--runtime-image',runtime,'--java-options','-Xmx2g','--java-options','--add-modules=jdk.jdi,jdk.compiler'])
        for document in ['README.md','FEATURES.md','THIRD_PARTY_NOTICES.md']:
            if (ROOT/document).is_file():shutil.copy2(ROOT/document,existing/document)
        shutil.copytree(resources/'META-INF/licenses',existing/'licenses',dirs_exist_ok=True)
        shutil.copy2(ROOT/'tools/dependencies.lock.json',existing/'dependencies.lock.json')
        (build/'packaged-runtime.txt').write_text(str(existing/'runtime'),encoding='utf8')
        print('Built',existing/'JarStudio.exe',flush=True)
        with zipfile.ZipFile(dist/'JarStudio-windows-x64.zip','w',zipfile.ZIP_DEFLATED,compresslevel=9) as output:
            for file in sorted(existing.rglob('*')):
                if file.is_file():output.write(file,'JarStudio/'+file.relative_to(existing).as_posix())
    with zipfile.ZipFile(dist/'JarStudio-jar.zip','w',zipfile.ZIP_DEFLATED,compresslevel=9) as output:
        output.write(jar,'JarStudio/JarStudio.jar')
        for document in ['README.md','FEATURES.md','THIRD_PARTY_NOTICES.md','run.cmd','build.ps1']:
            if (ROOT/document).is_file():output.write(ROOT/document,'JarStudio/'+document)
    source_zip=dist/'JarStudio-source.zip'
    source_files=[]
    for document in ['README.md','FEATURES.md','THIRD_PARTY_NOTICES.md','run.cmd','build.ps1','.gitignore']:
        if (ROOT/document).is_file():source_files.append(ROOT/document)
    for tree in [ROOT/'src',ROOT/'tools']:
        for path in sorted(tree.rglob('*')):
            if path.is_file():source_files.append(path)
    with zipfile.ZipFile(source_zip,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as output:
        for path in source_files:
            output.write(path,path.relative_to(ROOT).as_posix())
    digest=ROOT/'dist/SHA256SUMS.txt'
    lines=['%s  %s'%(hashlib.sha256(path.read_bytes()).hexdigest(),path.name) for path in sorted(dist.glob('*.zip'))+[jar]]
    digest.write_text('\n'.join(lines)+'\n',encoding='utf8')
    for portable in dist.glob('*.zip'):print('Packaged',portable,portable.stat().st_size,'bytes')


if __name__=='__main__':main()
