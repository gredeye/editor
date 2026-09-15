"""Expose actionable lint failures as annotations even if report downloads fail."""
from pathlib import Path
import xml.etree.ElementTree as ET

def escape(s):
    return str(s).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A').replace(',', '%2C').replace(':', '%3A')

for report in Path('app/build/reports').glob('lint-results-*.xml'):
    for issue in ET.parse(report).getroot().findall('issue'):
        if issue.get('severity') not in ('Error', 'Fatal'): continue
        loc = issue.find('location')
        path = Path(loc.get('file', 'app/src/main/AndroidManifest.xml')) if loc is not None else Path('app/src/main/AndroidManifest.xml')
        try: path = path.relative_to(Path.cwd())
        except ValueError: pass
        line = loc.get('line', '1') if loc is not None else '1'
        print(f'::error file={escape(path)},line={escape(line)},title={escape(issue.get("id"))}::{escape(issue.get("message"))}')
