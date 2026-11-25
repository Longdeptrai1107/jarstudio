"""Download unmodified upstream notices for the bundled dependencies."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
FILES={
    'ASM-LICENSE.txt':'https://asm.ow2.io/LICENSE.txt',
    'CFR-LICENSE.txt':'https://raw.githubusercontent.com/leibnitz27/cfr/0.152/LICENSE',
    'Vineflower-LICENSE.md':'https://raw.githubusercontent.com/Vineflower/vineflower/1.12.0/LICENSE.md',
    'ECJ-LICENSE.html':'https://www.eclipse.org/legal/epl-2.0/',
    'MicroEmulator-LGPL-2.1.txt':'https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt',
}


def download(item):
    name,url=item
    request=urllib.request.Request(url,headers={'User-Agent':'JarStudio-build/1.0'})
    with urllib.request.urlopen(request,timeout=40) as response:data=response.read()
    if len(data)<500:raise ValueError('Incomplete notice: '+name)
    target=ROOT/'src/main/resources/META-INF/licenses/upstream'/name
    target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
    return {'file':name,'url':url,'sha256':hashlib.sha256(data).hexdigest()}


if __name__=='__main__':
    with ThreadPoolExecutor(max_workers=5) as pool:rows=list(pool.map(download,FILES.items()))
    target=ROOT/'src/main/resources/META-INF/licenses/upstream/origins.json'
    target.write_text(json.dumps(rows,indent=2)+'\n',encoding='utf8')
    print('Saved',len(rows),'upstream notices')
