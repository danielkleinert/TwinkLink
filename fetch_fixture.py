import argparse
import json
import sys
import traceback
import os
import re
from typing import List, Dict, Any

from xled.control import HighControlInterface
from xled.discover import xdiscover
from xled.exceptions import DiscoverTimeout
from xled.device import Device

def sanitize_filename(name: str) -> str:
    return re.sub(r'[<>:"/\\|?*]', '', name).strip()

def save_fixture(coords: List[Dict[str, float]], output_path: str, scale: float, num_leds: int, device_info: Dict[str, Any], ip: str):
    chromatik_points = [
        {
            "x": pt.get('x', 0.0) * scale,
            "y": pt.get('y', 0.0) * scale,
            "z": pt.get('z', 0.0) * scale * -0.2
        }
        for pt in coords
    ]
        
    try:
        meta = dict(device_info).copy()
    except Exception:
        meta = {}
        
    for field in ['frame_rate', 'uptime']:
        meta.pop(field, None)
        
    meta.update({
        "scale": str(scale),
        "host": ip
    })

    device_name = device_info.get('device_name')
    tags = [v for v in [device_name, device_info.get('led_profile'), device_info.get('product_code')] if v]
        
    fixture = {
        "label": device_name or "Twinkly Custom",
        "tags": tags,
        "components": [
            {
                "type": "points",
                "coords": chromatik_points
            }
        ],
        "outputs": [
            {
                "protocol": "sacn",
                "host": '127.0.0.1',
                "universe": 1,
                "start": 0,
                "num": num_leds,
                "reverse": False,
                "byteOrder": "wrgb"
            }
        ],
        "meta": meta
    }
    
    print(f"Writing fixture to {output_path}...")
    with open(output_path, 'w') as f:
        json.dump(fixture, f, indent=2)

def process_device(ip: str, device_id: str, args: argparse.Namespace):
    print(f"Processing device {device_id} at {ip}...")
    try:
        ctrl = HighControlInterface(ip)
        dev = Device(ctrl)
        
        print("Fetching device info...")
        device_info = dev.device_info
        device_name = device_info.get('device_name', device_id)
        print(f"Device Name: {device_name}")
        
        print("Fetching layout...")
        layout = ctrl.get_led_layout()
        if 'coordinates' not in layout:
             raise ValueError("No 'coordinates' field found in layout response")
        coords = layout['coordinates']
        
        num_leds = len(coords)
        print(f"Found {num_leds} LEDs.")
        
        if num_leds == 0:
            print("Warning: Coordinate list is empty.")
            return

        output_dir = args.directory
        if not os.path.exists(output_dir):
            os.makedirs(output_dir)
            
        safe_name = sanitize_filename(device_name) or device_id
        filename = f"{safe_name}.lxf"
        output_path = os.path.join(output_dir, filename)
            
        save_fixture(coords, output_path, args.scale, num_leds, device_info, ip)
        print(f"Done with {device_name} ({device_id}).")
        
    except Exception as e:
        print(f"Error processing {device_id}: {e}")
        traceback.print_exc()

def main():
    parser = argparse.ArgumentParser(description='Fetch Twinkly layout and convert to Chromatik fixture.')
    parser.add_argument('--ip', help='IP address of the Twinkly device. If not provided, auto-discovery is used.')
    parser.add_argument('--directory', default='.', help='Output directory path (default: current directory)')
    parser.add_argument('--scale', type=float, default=100.0, help='Scale factor for coordinates (default: 100.0)')
    
    args = parser.parse_args()
    
    if args.ip:
        process_device(args.ip, "Manual", args)
    else:
        print("Discovering Twinkly devices...")
        found_any = False
        try:
            for device in xdiscover(timeout=5.0):
                found_any = True
                process_device(device.ip_address, device.id, args)
                
        except DiscoverTimeout:
            pass
        except Exception as e:
            traceback.print_exc()
            print(f"Discovery failed: {e!r}")
            sys.exit(1)
            
        if not found_any:
            print("No devices found.")
            sys.exit(1)

if __name__ == "__main__":
    main()
