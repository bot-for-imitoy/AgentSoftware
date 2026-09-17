"""一键生成全部图：python3 build_all.py [名字片段…]"""

import sys
import traceback

import dsl

MODULES = [
    "arch_1_packages",
    "arch_2_states",
    "uml_1_agent",
    "uml_2_sim",
    "uml_3_company",
    "uml_4_tools",
    "dfd_0_context",
    "dfd_1a_time_events",
    "dfd_1b_execution",
    "dfd_1c_lifecycle",
    "seq_1_task_life",
    "seq_2_talk_wait",
]


def main(argv):
    dsl.require_dot()
    wanted = argv or [m for m in MODULES]
    print(f"输出目录：{dsl.OUT}")
    failed = []
    for name in wanted:
        try:
            __import__(name).build()
        except ModuleNotFoundError:
            print(f"  – {name:<24} 跳过（还没写）")
        except Exception:                                  # noqa: BLE001
            failed.append(name)
            print(f"  ✘ {name}")
            traceback.print_exc()
    if failed:
        raise SystemExit(f"失败：{', '.join(failed)}")


if __name__ == "__main__":
    main(sys.argv[1:])
