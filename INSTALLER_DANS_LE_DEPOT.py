from pathlib import Path
import shutil

root = Path(__file__).resolve().parent
wf = root / ".github" / "workflows"
wf.mkdir(parents=True, exist_ok=True)

source = root / "build-v055.yml"
target = wf / "build-v055.yml"
shutil.copy2(source, target)

print("OK :", target)
print("Tu peux maintenant faire Stage all, Commit, Push.")
