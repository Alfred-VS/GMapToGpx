import requests
import json

url = "https://brouter.de/brouter?lonlats=8.5391,47.3774|8.5441,47.3784&profile=fastbike&alternativeidx=0&format=geojson"
headers = {"User-Agent": "Mozilla/5.0 GMapToGpx/1.0"}

response = requests.get(url, headers=headers)
print(f"Status Code: {response.status_code}")
if response.status_code == 200:
    data = response.json()
    messages = data['features'][0]['properties'].get('messages', [])
    print("Messages Sample:")
    for msg in messages[:10]:
        print(msg)

    # Check if there are surface tags
    has_surface = any("surface=" in m for m in messages)
    print(f"Contains surface tags: {has_surface}")

    # Save to file for further inspection
    with open("brouter_response.json", "w") as f:
        json.dump(data, f, indent=2)
else:
    print(response.text)
