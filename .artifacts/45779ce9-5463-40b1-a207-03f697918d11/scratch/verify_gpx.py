import re

def create_gpx(points, altitudes=None):
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
            sb.append(f'        <time>{time}</time>\n')
            sb.append('      </trkpt>\n')
        sb.append('    </trkseg>\n')
        sb.append('  </trk>\n')
    sb.append('</gpx>')
    return "".join(sb)

def process_gpx_content(gpx_content):
    points = []
    alts = []

    trkpt_block_regex = re.compile(r'<trkpt\s+lat=["\']([-+]?\d+\.\d+)["\']\s+lon=["\']([-+]?\d+\.\d+)["\'| ]>(.*?)</trkpt>', re.DOTALL)
    for match in trkpt_block_regex.finditer(gpx_content):
        lat = float(match.group(1))
        lon = float(match.group(2))
        content = match.group(3)
        ele_match = re.search(r'<ele>([-+]?\d+(\.\d+)?)</ele>', content)
        ele = float(ele_match.group(1)) if ele_match else 0.0
        points.append((lat, lon))
        alts.append(ele)

    if not points:
        wpt_block_regex = re.compile(r'<wpt\s+lat=["\']([-+]?\d+\.\d+)["\']\s+lon=["\']([-+]?\d+\.\d+)["\'| ]>(.*?)</wpt>', re.DOTALL)
        for match in wpt_block_regex.finditer(gpx_content):
            lat = float(match.group(1))
            lon = float(match.group(2))
            content = match.group(3)
            ele_match = re.search(r'<ele>([-+]?\d+(\.\d+)?)</ele>', content)
            ele = float(ele_match.group(1)) if ele_match else 0.0
            points.append((lat, lon))
            alts.append(ele)

    return points, alts

# Test
test_points = [(47.3774, 8.5391), (47.3784, 8.5441)]
test_alts = [400.5, 410.2]

gpx = create_gpx(test_points, test_alts)
print("Generated GPX:")
print(gpx)

parsed_points, parsed_alts = process_gpx_content(gpx)
print("\nParsed Points:", parsed_points)
print("Parsed Alts:", parsed_alts)

assert test_points == parsed_points
assert test_alts == parsed_alts
print("\nVerification successful!")
