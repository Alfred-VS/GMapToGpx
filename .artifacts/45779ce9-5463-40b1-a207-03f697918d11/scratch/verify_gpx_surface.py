import re

def create_gpx(points, altitudes=None, segments=None):
    time = "2026-07-20T21:22:18Z"
    sb = []
    sb.append('<?xml version="1.0" encoding="UTF-8"?>\n')
    sb.append('<gpx version="1.1" creator="GMapToGpx" xmlns="http://www.topografix.com/GPX/1/1">\n')
    sb.append(f'  <metadata><time>{time}</time></metadata>\n')

    if len(points) == 1:
        p = points[0]
        lat = f"{p[0]:.6f}"
        lon = f"{p[1]:.6f}"
        sb.append(f'  <wpt lat="{lat}" lon="{lon}">\n')
        sb.append('    <name>Google Maps Ort</name>\n')
        if altitudes and len(altitudes) > 0:
            sb.append(f'    <ele>{altitudes[0]}</ele>\n')
        if segments:
            seg = next((s for s in segments if s['startIndex'] <= 0 <= s['endIndex']), None)
            if seg:
                sb.append('    <extensions>\n')
                sb.append(f'      <surface>{seg["surface"]}</surface>\n')
                sb.append(f'      <highway>{seg["highway"]}</highway>\n')
                sb.append('    </extensions>\n')
        sb.append(f'    <time>{time}</time>\n')
        sb.append('  </wpt>\n')
    else:
        sb.append('  <trk>\n')
        sb.append('    <name>Google Maps Route</name>\n')
        sb.append('    <trkseg>\n')
        for i in range(len(points)):
            p = points[i]
            lat = f"{p[0]:.6f}"
            lon = f"{p[1]:.6f}"
            sb.append(f'      <trkpt lat="{lat}" lon="{lon}">\n')
            if altitudes and i < len(altitudes):
                sb.append(f'        <ele>{altitudes[i]}</ele>\n')
            if segments:
                seg = next((s for s in segments if s['startIndex'] <= i <= s['endIndex']), None)
                if seg:
                    sb.append('        <extensions>\n')
                    sb.append(f'          <surface>{seg["surface"]}</surface>\n')
                    sb.append(f'          <highway>{seg["highway"]}</highway>\n')
                    sb.append('        </extensions>\n')
            sb.append(f'        <time>{time}</time>\n')
            sb.append('      </trkpt>\n')
        sb.append('    </trkseg>\n')
        sb.append('  </trk>\n')
    sb.append('</gpx>')
    return "".join(sb)

def process_gpx_content(gpx_content):
    points = []
    alts = []
    surfaces = []
    highways = []

    trkpt_block_regex = re.compile(r'<trkpt\s+lat=["\']([-+]?\d+\.\d+)["\']\s+lon=["\']([-+]?\d+\.\d+)["\'| ]>(.*?)</trkpt>', re.DOTALL)
    for match in trkpt_block_regex.finditer(gpx_content):
        lat = float(match.group(1))
        lon = float(match.group(2))
        content = match.group(3)
        ele_match = re.search(r'<ele>([-+]?\d+(\.\d+)?)</ele>', content)
        ele = float(ele_match.group(1)) if ele_match else 0.0
        surf_match = re.search(r'<surface>(.*?)</surface>', content)
        surf = surf_match.group(1) if surf_match else ""
        hw_match = re.search(r'<highway>(.*?)</highway>', content)
        hw = hw_match.group(1) if hw_match else ""

        points.append((lat, lon))
        alts.append(ele)
        surfaces.append(surf)
        highways.append(hw)

    return points, alts, surfaces, highways

# Test
test_points = [(47.3774, 8.5391), (47.3784, 8.5441), (47.3794, 8.5491)]
test_alts = [400.0, 410.0, 420.0]
test_segments = [
    {'surface': 'asphalt', 'highway': 'cycleway', 'startIndex': 0, 'endIndex': 1},
    {'surface': 'gravel', 'highway': 'track', 'startIndex': 1, 'endIndex': 2}
]

gpx = create_gpx(test_points, test_alts, test_segments)
print("Generated GPX:")
print(gpx)

parsed_points, parsed_alts, parsed_surfs, parsed_hws = process_gpx_content(gpx)
print("\nParsed Points:", parsed_points)
print("Parsed Surfs:", parsed_surfs)
print("Parsed Highways:", parsed_hws)

assert parsed_surfs == ['asphalt', 'asphalt', 'gravel'] # Note: endIndex is inclusive in my logic
assert parsed_hws == ['cycleway', 'cycleway', 'track']

print("\nVerification successful!")
