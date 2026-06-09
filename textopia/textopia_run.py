import os
import time
import subprocess

# 📂 File paths configuration
TRIGGER_FILE = "D:\\Textopia_Files\\run.txt"   # Local trigger file on PC
SOURCE_DIR = "D:\\Textopia_Files"              # Local working directory
PHONE_CODE_DIR = "/storage/emulated/0/Android/data/com.example.texteditor/files/coded"  # App folder on phone

def run_and_capture(command, output_path, shell=False):
    """
    Run a command, capture its output (stdout + stderr),
    and save it to the given output file.
    """
    result = subprocess.run(command, capture_output=True, text=True, shell=shell)
    with open(output_path, "w", encoding="utf-8") as out_file:
        if result.stdout:
            out_file.write(result.stdout)
        if result.stderr:
            out_file.write(result.stderr)
    return result.returncode


print("🔥 Code Watcher is running... waiting for phone trigger file.")


while True:
    try:
        # 🛜 Try pulling trigger file from phone → PC
        subprocess.run(["adb", "pull", f"{PHONE_CODE_DIR}/run.txt", TRIGGER_FILE], check=True)
    except subprocess.CalledProcessError:
        # If not available yet, just wait and retry
        time.sleep(5)
        continue

    # ✅ If trigger file exists locally → start processing
    if os.path.exists(TRIGGER_FILE):
        try:
            # Read the filename from trigger file
            with open(TRIGGER_FILE, 'r') as f:
                filename = f.read().strip()

            file_path = os.path.join(SOURCE_DIR, filename)          # Full path on PC
            name_only, ext = os.path.splitext(filename)             # Separate name + extension
            output_txt = os.path.join(SOURCE_DIR, f"{name_only}.txt")

            print(f"🚀 Trigger received for: {filename}")

            # Pull the actual source code file from phone → PC
            print(f"⬇️ Downloading {filename} from phone...")
            subprocess.run(["adb", "pull", f"{PHONE_CODE_DIR}/{filename}", file_path], check=True)

            # If file is missing, log error & send back to phone
            if not os.path.exists(file_path):
                with open(output_txt, "w", encoding="utf-8") as f:
                    f.write("❌ File not found after pull!")
                subprocess.run(["adb", "push", output_txt, f"{PHONE_CODE_DIR}/{name_only}.txt"], check=True)

            else:
                try:
                    # ☕ Java files
                    if ext == ".java":
                        compile_result = subprocess.run(["javac", file_path], capture_output=True, text=True)
                        if compile_result.returncode != 0:
                            with open(output_txt, "w", encoding="utf-8") as f:
                                f.write(compile_result.stderr)
                        else:
                            run_and_capture(["java", "-cp", SOURCE_DIR, name_only], output_txt)

                    # 🐍 Python files
                    elif ext == ".py":
                        run_and_capture(["python", file_path], output_txt)

                    # 🔨 C files
                    elif ext == ".c":
                        exe_path = os.path.join(SOURCE_DIR, f"{name_only}.exe")
                        compile_result = subprocess.run(["gcc", file_path, "-o", exe_path], capture_output=True, text=True)
                        if compile_result.returncode != 0:
                            with open(output_txt, "w", encoding="utf-8") as f:
                                f.write(compile_result.stderr)
                        else:
                            run_and_capture([exe_path], output_txt)

                    # ⚙️ C++ files
                    elif ext == ".cpp":
                        exe_path = os.path.join(SOURCE_DIR, f"{name_only}.exe")
                        compile_result = subprocess.run(["g++", file_path, "-o", exe_path], capture_output=True, text=True)
                        if compile_result.returncode != 0:
                            with open(output_txt, "w", encoding="utf-8") as f:
                                f.write(compile_result.stderr)
                        else:
                            run_and_capture([exe_path], output_txt)

                    # 🟣 Kotlin files
                    elif ext == ".kt":
                        jar_path = os.path.join(SOURCE_DIR, f"{name_only}.jar")
                        compile_result = subprocess.run(["kotlinc", file_path, "-include-runtime", "-d", jar_path], capture_output=True, text=True)
                        if compile_result.returncode != 0:
                            with open(output_txt, "w", encoding="utf-8") as f:
                                f.write(compile_result.stderr)
                        else:
                            run_and_capture(["java", "-jar", jar_path], output_txt)

                    # ❓ Unsupported file types
                    else:
                        with open(output_txt, "w", encoding="utf-8") as f:
                            f.write(f"❓ Unsupported file type: {ext}")

                except Exception as e:
                    # Handle any unexpected runtime error
                    with open(output_txt, "w", encoding="utf-8") as f:
                        f.write(f"💥 Error: {str(e)}")

                # Push the execution result back to the phone
                subprocess.run(["adb", "push", output_txt, f"{PHONE_CODE_DIR}/{name_only}.txt"], check=True)

        except Exception as ex:
            print("⚠️ General Error:", ex)

        # Remove trigger to reset system
        os.remove(TRIGGER_FILE)
        print("✅ Task completed. Waiting for next trigger...\n")

    # Small delay before re-checking
    time.sleep(5)
