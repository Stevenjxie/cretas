# -*- coding: utf-8 -*-
"""Test auto-learning mechanism"""
import asyncio
import sys
import os
import io

# Fix encoding for Windows console.
# ⚠️ 只在**真的以脚本运行**时才改 —— pytest 下 sys.stdout 是它的捕获对象, 在导入期
#    把 sys.stdout.buffer 重新包一层并改绑, 会掐断 pytest 的捕获机制: 之后它对自己的
#    tmpfile `seek(0)` 就抛 `ValueError: I/O operation on closed file`, **整个 pytest
#    进程当场归零**(不是这个用例失败, 是整轮测量没了)。
#    python-gate.yml 因此把整个 smartbi/tests/(75 个文件)排除在门禁之外 —— 一个导入
#    期副作用挡住了 75 个文件几个月。
#    判据: **模块导入期不要改全局 IO/环境**; 要改就放进 `if __name__ == "__main__"`。
if sys.platform == 'win32' and "pytest" not in sys.modules:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

# Add smartbi to path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from services.data_cleaner import DataCleaner  # noqa: E402


async def test_auto_learning():
    """Test auto-learning of new rules"""
    print("=" * 60)
    print("Test Auto-Learning Mechanism")
    print("=" * 60)

    cleaner = DataCleaner()

    # Test data with values that need cleaning
    test_data = [
        {"name": "A", "code": "CODE-001", "value": "$1,234.56"},
        {"name": "B", "code": "CODE-002", "value": "$5,678.90"},
        {"name": "C", "code": "CODE-003", "value": "$9,012.34"},
    ]

    # Create a rule name that doesn't exist
    rule_name = "remove_currency_symbol"

    print(f"\n1. Check if rule '{rule_name}' exists...")
    exists = rule_name in cleaner.rules
    print(f"   Rule exists: {exists}")

    if not exists:
        print("\n2. Generating new rule...")

        # Call auto-learning
        issue_type = "currency_format"
        description = "Values contain currency symbols and thousand separators like $1,234.56"
        examples = ["$1,234.56", "$5,678.90", "$9,012.34"]

        rule_func = await cleaner._generate_and_register_rule(
            rule_name, issue_type, description, examples
        )

        if rule_func:
            print("   [OK] Rule generated successfully!")
            print(f"   Function: {rule_func}")

            # Test the new rule
            print("\n3. Testing new rule...")
            print(f"   Original data: {test_data}")

            changes = rule_func(test_data, ["value"])
            print(f"   Changes made: {changes}")
            print(f"   Cleaned data: {test_data}")

            # Check if rule is registered
            print("\n4. Check if rule is registered...")
            print(f"   '{rule_name}' in rules: {rule_name in cleaner.rules}")

            return True
        else:
            print("   [FAIL] Rule generation failed!")
            return False
    else:
        print("   Rule already exists, skipping generation test")
        return True


async def test_security_validation():
    """Test security validation"""
    print("\n" + "=" * 60)
    print("Test Security Validation")
    print("=" * 60)

    cleaner = DataCleaner()

    # Test dangerous code
    dangerous_codes = [
        ("import os", "import os\ndef rule_test(data, cols): return 0"),
        ("from subprocess", "from subprocess import call\ndef rule_test(data, cols): return 0"),
        ("eval()", "def rule_test(data, cols): eval('1+1'); return 0"),
        ("exec()", "def rule_test(data, cols): exec('pass'); return 0"),
        ("open()", "def rule_test(data, cols): open('/etc/passwd'); return 0"),
    ]

    all_blocked = True
    for name, code in dangerous_codes:
        is_safe = cleaner._validate_rule_code(code)
        status = "[BLOCKED]" if not is_safe else "[WARNING: NOT BLOCKED]"
        print(f"   {status} {name}")
        if is_safe:
            all_blocked = False

    result = "[OK] All dangerous code blocked" if all_blocked else "[FAIL] Some dangerous code passed"
    print(f"\n   Security validation: {result}")
    return all_blocked


async def test_valid_rule_execution():
    """Test valid rule execution"""
    print("\n" + "=" * 60)
    print("Test Valid Rule Execution")
    print("=" * 60)

    cleaner = DataCleaner()

    # A safe rule code (without import statements)
    valid_code = '''
def rule_test_valid(data, cols):
    """Test rule"""
    changes = 0
    for row in data:
        for col in cols:
            if col in row and isinstance(row[col], str):
                new_val = row[col].upper()
                if new_val != row[col]:
                    row[col] = new_val
                    changes += 1
    return changes
'''

    print("   Validating code safety...")
    is_safe = cleaner._validate_rule_code(valid_code)
    print(f"   Safety check: {'[OK] Passed' if is_safe else '[FAIL] Failed'}")

    if is_safe:
        print("   Executing rule code...")
        rule_func = cleaner._parse_and_execute_rule("test_valid", valid_code)

        if rule_func:
            print(f"   [OK] Rule parsed successfully: {rule_func}")

            # Test execution
            test_data = [{"text": "hello"}, {"text": "world"}]
            changes = rule_func(test_data, ["text"])
            print(f"   Execution result: {changes} changes")
            print(f"   Data: {test_data}")
            return True
        else:
            print("   [FAIL] Rule parsing failed")
            return False

    return False


async def main():
    print("\n" + "=" * 70)
    print("          Auto-Learning Mechanism Full Test")
    print("=" * 70)

    results = []

    # Test 1: Security validation
    results.append(("Security Validation", await test_security_validation()))

    # Test 2: Valid rule execution
    results.append(("Valid Rule Execution", await test_valid_rule_execution()))

    # Test 3: Auto-learning (requires LLM API)
    try:
        results.append(("Auto-Learning", await test_auto_learning()))
    except Exception as e:
        print(f"\nAuto-learning test failed: {e}")
        import traceback
        traceback.print_exc()
        results.append(("Auto-Learning", False))

    # Summary
    print("\n" + "=" * 70)
    print("Test Results Summary")
    print("=" * 70)
    for name, passed in results:
        status = "[OK] Passed" if passed else "[FAIL] Failed"
        print(f"   {name}: {status}")

    all_passed = all(r[1] for r in results)
    print(f"\n   Overall: {'[OK] All tests passed' if all_passed else '[FAIL] Some tests failed'}")


if __name__ == "__main__":
    asyncio.run(main())
