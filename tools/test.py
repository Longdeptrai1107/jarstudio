"""Run the integration suite and optional Windows GUI / Java ME smoke tests."""
import argparse
import os
from pathlib import Path
import subprocess
import build

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--packaged',action='store_true');parser.add_argument('--gui',action='store_true');args=parser.parse_args()
    if args.packaged:
        home=ROOT/'dist/windows/JarStudio/runtime';jar=ROOT/'dist/windows/JarStudio/app/JarStudio.jar';suffix='packaged'
    else:
        home=build.java_home();jar=ROOT/'dist/JarStudio.jar';suffix='jdk'
    classes=ROOT/'build/test-classes'
    if not jar.is_file() or not (classes/'dev/jarstudio/IntegrationTests.class').is_file():raise RuntimeError('Build first: python tools/build.py --tests'+(' --exe' if args.packaged else ''))
    cp=os.pathsep.join([str(jar),str(classes)])
    tasks=[('dev.jarstudio.IntegrationTests',ROOT/('build/test-work-'+suffix))]
    if args.gui:tasks.append(('dev.jarstudio.GuiSmoke',ROOT/('build/gui-smoke-'+suffix)))
    for name,target in tasks:
        subprocess.run([str(home/'bin/java.exe'),'--add-modules=jdk.jdi,jdk.compiler','-Djarstudio.test=true','-Djarstudio.home='+str(ROOT/('build/test-home-'+suffix)),'-cp',cp,name,str(target)],cwd=ROOT,check=True,timeout=300)


if __name__=='__main__':main()
