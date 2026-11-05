//! Resident memory of a process and its descendants, read from `ps` (macOS and Linux).

use std::collections::HashMap;
use std::process::Command;

/// Total RSS in bytes of `root` and every descendant, or `None` once `root` is gone.
pub fn tree_rss(root: u32) -> Option<u64> {
    let output = Command::new("ps").args(["-A", "-o", "pid=,ppid=,rss="]).output().ok()?;
    sum_tree(&String::from_utf8_lossy(&output.stdout), root)
}

/// `listing` holds `pid ppid rss_kib` lines.
fn sum_tree(listing: &str, root: u32) -> Option<u64> {
    let mut rss = HashMap::new();
    let mut children: HashMap<u32, Vec<u32>> = HashMap::new();
    for line in listing.lines() {
        let fields: Vec<u64> = line.split_whitespace().filter_map(|field| field.parse().ok()).collect();
        if let [pid, ppid, kib] = fields[..] {
            rss.insert(pid as u32, kib * 1024);
            children.entry(ppid as u32).or_default().push(pid as u32);
        }
    }
    rss.get(&root)?;
    let (mut total, mut stack) = (0, vec![root]);
    while let Some(pid) = stack.pop() {
        total += rss.get(&pid).copied().unwrap_or(0);
        stack.extend(children.get(&pid).into_iter().flatten());
    }
    Some(total)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sums_descendants_only() {
        let listing = "  1 0 100\n 10 1 2\n 11 10 3\n 12 11 4\n 20 1 50\n bad line\n";
        assert_eq!(sum_tree(listing, 10), Some(9 * 1024));
        assert_eq!(sum_tree(listing, 12), Some(4 * 1024));
        assert_eq!(sum_tree(listing, 99), None);
    }

    #[test]
    fn reads_this_process() {
        assert!(tree_rss(std::process::id()).is_some_and(|bytes| bytes > 0));
    }
}
