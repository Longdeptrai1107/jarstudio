"""Download pinned compiler/editor dependencies from Maven Central."""
from concurrent.futures import ThreadPoolExecutor,as_completed
import hashlib
import json
from pathlib import Path
import shutil
import urllib.error
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[1]
PACKAGES=[
    ('asm','org/ow2/asm/asm','9.11.1'),
    ('asm-tree','org/ow2/asm/asm-tree','9.11.1'),
    ('asm-util','org/ow2/asm/asm-util','9.11.1'),
    ('asm-analysis','org/ow2/asm/asm-analysis','9.11.1'),
    ('rsyntaxtextarea','com/fifesoft/rsyntaxtextarea','4.0.2'),
    ('cfr','org/benf/cfr','0.152'),
    ('vineflower','org/vineflower/vineflower','1.12.0'),
    ('ecj','org/eclipse/jdt/ecj','3.33.0'),
    ('cldcapi11','org/microemu/cldcapi11','2.0.4'),
    ('midpapi20','org/microemu/midpapi20','2.0.4'),
    ('microemulator','org/microemu/microemulator','2.0.4'),
]


def download(item):
    name,coordinate,version=item
    filename=f'{name}-{version}.jar'
    target=ROOT/'lib'/filename;url=f'https://repo.maven.apache.org/maven2/{coordinate}/{version}/{filename}'
    if not target.exists():
        with urllib.request.urlopen(url,timeout=90) as response:content=response.read()
        with zipfile.ZipFile(__import__('io').BytesIO(content)) as check:
            if check.testzip():raise ValueError('Artifact CRC error: '+name)
        target.write_bytes(content)
    content=target.read_bytes();sha=hashlib.sha256(content).hexdigest()
    verified=None
    for algorithm in ['sha512','sha256','sha1']:
        try:
            expected=urllib.request.urlopen(url+'.'+algorithm,timeout=30).read().decode().strip().split()[0].lower()
            if hashlib.new(algorithm,content).hexdigest()!=expected:raise ValueError('Maven checksum mismatch: '+name)
            verified=algorithm;break
        except urllib.error.HTTPError as error:
            if error.code!=404:raise
    if not verified:raise ValueError('No upstream checksum available: '+name)
    return {'name':name,'version':version,'filename':filename,'url':url,'sha256':sha,'upstream_checksum_verified':verified,'bytes':len(content)}


def main():
    (ROOT/'lib').mkdir(parents=True,exist_ok=True)
    rows=[]
    with ThreadPoolExecutor(max_workers=5) as pool:
        for job in as_completed([pool.submit(download,item) for item in PACKAGES]):
            row=job.result();rows.append(row);print(row['filename']+' verified',flush=True)
    rows.sort(key=lambda row:row['name'])
    (ROOT/'tools/dependencies.lock.json').write_text(json.dumps({'packages':rows},indent=2)+'\n',encoding='utf8')


if __name__=='__main__':main()
