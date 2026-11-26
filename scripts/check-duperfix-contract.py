#!/usr/bin/env python3
"""Read-only source contract preflight. NOT Gradle, JUnit or NeoForge compilation."""
from pathlib import Path
import re,sys
root=Path(__file__).resolve().parents[1]
release='0.6.1-originlastFix'
errors=[]
def source(name):return (root/name).read_text(encoding='utf8')
mod=source('src/main/resources/META-INF/neoforge.mods.toml')
core=source('src/main/java/com/physicaldestroydeform/PhysicalDestroyDeform.java')
gradle=source('build.gradle')
readme=source('README.md')
if not re.search(r'^version="'+re.escape(release)+r'"$',mod,re.M): errors.append('NeoForge Mods-screen version mismatch')
if f'BUILD_VERSION = "{release}"' not in core or f'revision={release} ' not in core: errors.append('in-game log version mismatch')
if f"version = '{release}'" not in gradle or f"'PDD-Revision': '{release}'" not in gradle: errors.append('Gradle/JAR revision mismatch')
if "archiveClassifier.set('')" not in gradle: errors.append('duplicate Gradle JAR classifier suffix')
if not readme.startswith('# PDD '+release) or 'physical-destroy-deform-'+release+'.jar' not in readme:
    errors.append('README current release mismatch')

# Prevent the exact compilation fault from this release: unqualified, package-external
# DamageModel references without an import. This static guard augments, never
# replaces, real compileTestJava.
files=list((root/'src/test/java').rglob('*.java'))
for path in files:
    text=path.read_text(encoding='utf8')
    stripped=re.sub(r'(?s)/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"', '',text)
    if re.search(r'\bDamageModel\s*\.',stripped) and not re.search(r'^\s*package\s+com\.physicaldestroydeform\s*;',text,re.M) and not re.search(r'^\s*import\s+com\.physicaldestroydeform\.(?:DamageModel|\*)\s*;',text,re.M) and not re.search(r'\bcom\.physicaldestroydeform\.DamageModel\.',stripped):
        errors.append(str(path.relative_to(root))+': missing DamageModel import')
count=sum(len(re.findall(r'@Test\b',p.read_text(encoding='utf8'))) for p in files)
if count != 816: errors.append(f'JUnit method inventory changed unexpectedly: {count} vs expected 816')
if errors:
    print('PDD static contract FAIL:',*errors,sep='\n - ',file=sys.stderr)
    raise SystemExit(1)
print(f'PDD static contract PASS: {release}; {count} @Test methods, {len(files)} files; metadata and DamageModel imports checked')
