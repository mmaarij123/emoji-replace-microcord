"""Bundle EmojiReplace artwork from a checkout of DevilBro's BetterDiscordAddons."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha256
from io import BytesIO
from pathlib import Path
import re
from tempfile import TemporaryDirectory

from PIL import Image


PROVIDERS = (
    "Apple", "BlobMoji", "Facebook", "Google", "Huawei", "JoyPixels", "Microsoft",
    "Microsoft-3D", "OpenMoji", "Samsung", "Samsung-Old", "Toss", "WhatsApp",
)
CATEGORIES = ("people", "nature", "food", "activity", "travel", "objects", "symbols", "flags")
NAME = re.compile(r"[a-z0-9_+\-]+\.png", re.ASCII)
ASSETS = Path(__file__).resolve().parents[1] / "src/main/assets"


def provider_files(root, provider):
    files = {}
    # Keep the category precedence of the original theme when filenames are reused.
    for category in CATEGORIES:
        directory = root / provider / category
        for file in directory.glob("*.png"):
            if NAME.fullmatch(file.name):
                files[file.stem] = file
    if len(files) < 1000 or "grinning" not in files:
        raise ValueError(f"Incomplete provider checkout: {provider}")
    return sorted(files.items())


def encode(item):
    name, file = item
    data = file.read_bytes()
    origin = sha256(data).digest()
    with Image.open(BytesIO(data)) as image:
        rgba = image.convert("RGBA")
        rgba.thumbnail((96, 96), Image.Resampling.LANCZOS)
        output = BytesIO()
        rgba.save(output, format="WEBP", quality=88, method=3)
    return name, origin, output.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    args = parser.parse_args()
    root = args.checkout / "Themes/EmojiReplace/emojis"
    providers = {provider: provider_files(root, provider) for provider in PROVIDERS}

    ASSETS.mkdir(parents=True, exist_ok=True)
    index_dir = ASSETS / "emoji-index"
    index_dir.mkdir(exist_ok=True)
    with TemporaryDirectory(prefix="emoji-art-", dir=ASSETS.parent.parent) as directory:
        staging = Path(directory)
        bank = staging / "emoji-art.emojipack"
        offsets = {}
        with bank.open("wb") as pack, ThreadPoolExecutor(max_workers=8) as workers:
            for provider, images in providers.items():
                with (staging / f"{provider}.tsv").open("w", encoding="ascii", newline="\n") as index:
                    for name, key, image in workers.map(encode, images):
                        if key not in offsets:
                            offsets[key] = pack.tell(), len(image)
                            pack.write(image)
                        offset, length = offsets[key]
                        index.write(f"{name}\t{offset}\t{length}\n")
                print(f"{provider}: {len(images)} emojis", flush=True)
        for provider in PROVIDERS:
            (staging / f"{provider}.tsv").replace(index_dir / f"{provider}.tsv")
        bank.replace(ASSETS / bank.name)
        print(f"{len(offsets)} unique images, {int((ASSETS / bank.name).stat().st_size / 2**20)} MiB", flush=True)


if __name__ == "__main__":
    main()
