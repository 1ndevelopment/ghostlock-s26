# Porting Notes

Notes for porting this exploit to another device or firmware version.

## Where parameters live

- **Fixed constants** (shared by all devices): `exploit/src/params.h`
- **Per-kernel-line parameters** and the **build → line map**: `exploit/src/params_table.c`

A new firmware only needs a new `device_map` entry (`build_id` + `device` + `line_id`).
A new kernel binary (new line) additionally needs the per-line table below re-derived.

## Per-kernel-line parameters (`struct kernel_line`)

### Slide (tracefs)

| Field                       | Source                                                                                                                                 |
| --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `tracefs_event_id`          | runtime id of the `sched_blocked_reason` trace event — read it from the device tracefs (authoritative) or derive offline from kallsyms |
| `tracefs_worker_caller_off` | kallsyms + disassembly of `worker_thread`: the instruction after the blocking `bl schedule` (the recorded caller is the return PC)     |

### Seed symbols (kallsyms)

| Field                 | Purpose                                       |
| --------------------- | --------------------------------------------- |
| `root_task_group_off` | seeded into the fake task (`task_group` slot) |
| `init_task_off`       | seeded as `waiter_task` / `pi_top_task`       |

### Attr carrier (kallsyms)

| Field                                            | Purpose                                             |
| ------------------------------------------------ | --------------------------------------------------- |
| `misc_list_off`                                  | misc device list head (attach/restore anchor)       |
| `uinput_misc_off`                                | uinput misc node (carrier anchor)                   |
| `simple_attr_read_off` / `simple_attr_write_off` | simple_attr read/write callbacks for the fake nodes |
| `debugfs_u64_get_off` / `debugfs_u64_set_off`    | debugfs u64 accessors                               |
| `debugfs_u64_format_off`                         | rodata `"%llu\n"` string                            |
| `default_llseek_off`                             | llseek slot of the fake fops                        |

### Fake fops slots (kallsyms)

`copy_splice_read_off`, `configfs_read_iter_off`, `configfs_bin_write_iter_off`, and the six `ashmem_*` slots
(`ioctl`, `compat_ioctl`, `mmap`, `open`, `release`, `show_fdinfo`).

### UMH root (kallsyms)

| Field                               | Purpose                                        |
| ----------------------------------- | ---------------------------------------------- |
| `call_usermodehelper_exec_work_off` | forged `work.func`                             |
| `system_unbound_wq_off`             | workqueue topology root — **differs per line** |
| `selinux_enforcing_off`             | `selinux_state.enforcing` (permissive write)   |

### Device-verified

| Field          | Source                                             |
| -------------- | -------------------------------------------------- |
| `uinput_minor` | minor number of `/dev/uinput` on the target device |

## Fixed constants (`params.h`)

`KIMAGE_TEXT_BASE`, `P0_PAGE_OFFSET`, `P0_PHYS_OFFSET`, `P0_KERNEL_PHYS_LOAD`,
`KERNELSNITCH_IDENTITY_START/END`, `DIRECT_MAP_BASE/END`, `VMEMMAP_START` — stable on the
Samsung 6.12 GKI family; re-check only when moving to a new kernel family.

## Layout and BTF constants

- Payload page layout (`LOCK_OFF`/`W0_OFF`/`FAKE_TASK_OFF`/`FOPS_OFF`/attr carrier offsets)
- Kernel struct layouts (`WQ_*`, `PWQ_*`, `POOL_*`, `WORK_*`, `FOPS_*`, `FAKE_WAITER_*`,
  `FAKE_TASK_*`): derived from the BTF embedded in the kernel image — re-verify with BTF
  when changing the route or porting to a new kernel family.

## Reference values (current targets)

| Field                               | cn         | intl       | exynos     |
| ----------------------------------- | ---------- | ---------- | ---------- |
| `tracefs_event_id`                  | 110        | 110        | 110        |
| `tracefs_worker_caller_off`         | 0x103878   | 0x103878   | 0x1040D4   |
| `root_task_group_off`               | 0x02763580 | 0x02763580 | 0x02772D80 |
| `init_task_off`                     | 0x0252D040 | 0x0252D040 | 0x0253D040 |
| `misc_list_off`                     | 0x0264CFE0 | 0x0264CFE0 | 0x0265CA20 |
| `uinput_misc_off`                   | 0x02671800 | 0x02671800 | 0x02680110 |
| `simple_attr_read_off`              | 0x00484D3C | 0x00484D3C | 0x00486B64 |
| `simple_attr_write_off`             | 0x00484E8C | 0x00484E8C | 0x00486CB4 |
| `debugfs_u64_get_off`               | 0x00675384 | 0x00675384 | 0x00677628 |
| `debugfs_u64_set_off`               | 0x00675398 | 0x00675398 | 0x0067763C |
| `default_llseek_off`                | 0x0043FB54 | 0x0043FB54 | 0x0044173C |
| `debugfs_u64_format_off`            | 0x0148C338 | 0x0148C338 | 0x0148C6B8 |
| `call_usermodehelper_exec_work_off` | 0x000F8F0C | 0x000F8F0C | 0x000F9768 |
| `system_unbound_wq_off`             | 0x01915250 | 0x01914250 | 0x01917250 |
| `selinux_enforcing_off`             | 0x027AFB08 | 0x027AFB08 | 0x027BFAF0 |
| `copy_splice_read_off`              | 0x0049288C | 0x0049288C | 0x004946B4 |
| `configfs_read_iter_off`            | 0x00518860 | 0x00518860 | 0x0051A4A4 |
| `configfs_bin_write_iter_off`       | 0x00518E0C | 0x00518E0C | 0x0051AA50 |
| `ashmem_ioctl_off`                  | 0x00E0C724 | 0x00E0C724 | 0x0044173C |
| `ashmem_compat_ioctl_off`           | 0x00E0CD0C | 0x00E0CD0C | 0x0044173C |
| `ashmem_mmap_off`                   | 0x00E0CD88 | 0x00E0CD88 | 0x0044173C |
| `ashmem_open_off`                   | 0x00E0CDE4 | 0x00E0CDE4 | 0x00E0F828 |
| `ashmem_release_off`                | 0x00E0C7E4 | 0x00E0C7E4 | 0x00E0F860 |
| `ashmem_show_fdinfo_off`            | 0x00E0CCE4 | 0x00E0CCE4 | 0x0044173C |
| `uinput_minor`                      | 223        | 223        | 223        |

The Exynos line's `ashmem_*` slots share placeholder values; verify them before relying
on that line.

## Sources at a glance

- **kallsyms** — from the boot image kernel: all `*_off` symbol offsets.
- **BTF** — embedded in the kernel image: struct layouts.
- **tracefs** — the `sched_blocked_reason` event id can be read from the device.
- **On-device verification** — `uinput_minor`, `system_unbound_wq_off` (per-line values).
