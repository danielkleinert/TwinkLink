import argparse
import json
import sys
import traceback
import os
import re
import base64
import gzip
import requests
from typing import List, Dict, Any

TWINKLY_API_BASE = "https://api.twinkly.com"

def authenticate_cloud(email: str, password: str) -> str:
    print(f"Authenticating as {email}...")
    url = f"{TWINKLY_API_BASE}/v2/auth"
    payload = {
        "username": email,
        "password": password
    }
    headers = {
        "Content-Type": "application/json"
    }
    
    try:
        response = requests.post(url, json=payload, headers=headers)
        response.raise_for_status()
        data = response.json()
        return data["access_token"]
    except Exception as e:
        print(f"Authentication failed: {e}")
        if hasattr(e, 'response') and e.response:
             print(f"Response: {e.response.text}")
        raise

def fetch_cloud_devices(token: str) -> List[Dict[str, Any]]:
    print("Fetching devices from cloud...")
    url = f"{TWINKLY_API_BASE}/v3/objects?mine=true&fields=layout,devices,capabilities"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }
    response = requests.get(url, headers=headers)
    response.raise_for_status()
    data = response.json()
    return data.get("objects")

def decode_coordinates(encoded_coords: str) -> List[Dict[str, float]]:
    decoded = base64.b64decode(encoded_coords)
    decompressed = gzip.decompress(decoded)
    return json.loads(decompressed)

def process_cloud_device(device_data: Dict[str, Any], args: argparse.Namespace):
    device_name = device_data.get("name", "Unknown")
    device_id = str(device_data.get("id", "Unknown"))
    print(f"Processing cloud device {device_name} ({device_id})...")
    
    layout = device_data.get("layout", {})
    if not layout:
        print(f"No layout found for {device_name}")
        return
    encoded_coords = layout.get("coords")
    if not encoded_coords:
        print(f"No coordinates found for {device_name}")
        return
    coords = decode_coordinates(encoded_coords)
    if len(coords) == 0:
        print("Warning: Coordinate list is empty.")
        return

    aspect_xy = layout.get("aspectXY", 1.0)
    aspect_xz = layout.get("aspectXZ", 1.0)
    
    # Use the first device for metadata
    device_info = device_data["devices"][0]["device"]

    output_dir = args.directory
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)
        
    safe_name = sanitize_filename(device_name) or device_id
    filename = f"{safe_name}.lxf"
    output_path = os.path.join(output_dir, filename)
        
    save_fixture(coords, output_path, device_info, aspect_xy, aspect_xz)
    print(f"Done with {device_name} ({device_id}).")


def sanitize_filename(name: str) -> str:
    return re.sub(r'[<>:"/\\|?*]', '', name).strip()

def save_fixture(coords: List[Dict[str, float]], output_path: str, device_info: Dict[str, Any], aspect_xy: float = 1.0, aspect_xz: float = 1.0):
    scale = 100
    fixture = {
        "label": device_info.get('name') or "Twinkly",
        "tags": [v for v in [(device_info.get('name')), device_info.get('ledProfile'), device_info.get('productCode')] if v],
        "components": [
            {
                "type": "points",
                "coords": [
                    {
                        "x": pt['x'] * scale,
                        "y": pt['y'] * scale / aspect_xy * 2,
                        "z": pt['z'] * scale / aspect_xz * -1
                    }
                    for pt in coords
                ]
            }
        ],
        "outputs": [
            {
                "protocol": "sacn",
                "host": '127.0.0.1',
                "universe": 1,
                "start": 0,
                "num": device_info['ledCount'],
                "reverse": False,
                "byteOrder": "wrgb"
            }
        ],
        "meta": {k: str(v) for k, v in device_info.items()}
    }
    
    print(f"Writing fixture to {output_path}...")
    with open(output_path, 'w') as f:
        json.dump(fixture, f, indent=2)



def main():
    parser = argparse.ArgumentParser(description='Fetch Twinkly layout and convert to Chromatik fixture.')
    parser.add_argument('--directory', default='.', help='Output directory path (default: current directory)')
    parser.add_argument('--email', required=True, help='Twinkly account email')
    parser.add_argument('--password', required=True, help='Twinkly account password')
    
    args = parser.parse_args()
    
    try:
        token = authenticate_cloud(args.email, args.password)
        devices = fetch_cloud_devices(token)
        for device in devices:
            process_cloud_device(device, args)
    except Exception as e:
        print(f"Cloud fetching failed: {e}")
        traceback.print_exc()
        sys.exit(1)

if __name__ == "__main__":
    main()
