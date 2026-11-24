"""Create platform icons from the official logo artwork."""
from pathlib import Path
from PIL import Image

assets = Path(__file__).resolve().parents[1] / 'assets'
image = Image.open(assets / 'logo.png').convert('RGBA')
image.save(assets / 'icon.ico', sizes=[(n, n) for n in (16, 24, 32, 48, 64, 128, 256)])
image.resize((1024, 1024), Image.Resampling.LANCZOS).save(assets / 'icon.icns')
