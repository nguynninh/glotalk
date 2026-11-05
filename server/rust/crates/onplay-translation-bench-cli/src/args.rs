//! Command-line parsing without extra dependencies.

use std::path::PathBuf;
use std::time::Duration;

use onplay_translation_bench::Options;

pub const USAGE: &str = "\
Usage:
  onplay-translation-bench corpus [--sessions DIR] [--derive LANGS] [--out FILE]
  onplay-translation-bench run --label NAME [options] -- COMMAND...
  onplay-translation-bench compare BASELINE_DIR CANDIDATE_DIR [--diff FILE]

corpus   Unique segments from recorded sessions (default: sessions/ -> build/translation-bench/corpus.jsonl).
         --derive also sends recorded Vietnamese translations to these languages (default: en,zh; '' for none).
run      Starts COMMAND (the router) fresh for every run, sends every corpus item once, stops it.
           --corpus FILE   (default: build/translation-bench/corpus.jsonl)
           --out DIR       (default: build/translation-bench/<label>)
           --runs N        (default: 3)
           --url URL       Router endpoint (default: http://127.0.0.1:5002)
           --root DIR      Working directory for COMMAND (default: .)
           --startup-timeout SECONDS (default: 900)
compare  Identical outputs, latency and memory of two run folders; --diff writes differing items.";

pub enum Command {
    Corpus { sessions: PathBuf, derive: Vec<String>, out: PathBuf },
    Run { corpus: PathBuf, options: Options },
    Compare { baseline: PathBuf, candidate: PathBuf, diff: Option<PathBuf> },
}

const BENCH_DIR: &str = "build/translation-bench";

pub fn parse(mut raw: impl Iterator<Item = String>) -> Result<Command, String> {
    let command = raw.next().ok_or("a command is required")?;
    match command.as_str() {
        "corpus" => {
            let (mut sessions, mut derive, mut out) =
                (PathBuf::from("sessions"), vec!["en".to_owned(), "zh".to_owned()], PathBuf::from(BENCH_DIR).join("corpus.jsonl"));
            for (flag, value) in pairs(raw)? {
                match flag.as_str() {
                    "--sessions" => sessions = value.into(),
                    "--derive" => derive = value.split(',').filter(|code| !code.is_empty()).map(str::to_owned).collect(),
                    "--out" => out = value.into(),
                    _ => return Err(format!("unknown option {flag}")),
                }
            }
            Ok(Command::Corpus { sessions, derive, out })
        }
        "run" => {
            let mut options = Options {
                label: String::new(),
                command: Vec::new(),
                root: ".".into(),
                endpoint: "http://127.0.0.1:5002".into(),
                runs: 3,
                out: PathBuf::new(),
                startup_timeout: Duration::from_secs(900),
            };
            let mut corpus = PathBuf::from(BENCH_DIR).join("corpus.jsonl");
            let mut flags = Vec::new();
            for arg in raw.by_ref() {
                if arg == "--" {
                    break;
                }
                flags.push(arg);
            }
            options.command = raw.collect();
            for (flag, value) in pairs(flags.into_iter())? {
                match flag.as_str() {
                    "--label" => options.label = value,
                    "--corpus" => corpus = value.into(),
                    "--out" => options.out = value.into(),
                    "--runs" => options.runs = number(&flag, &value)?,
                    "--url" => options.endpoint = value,
                    "--root" => options.root = value.into(),
                    "--startup-timeout" => options.startup_timeout = Duration::from_secs(number(&flag, &value)?),
                    _ => return Err(format!("unknown option {flag}")),
                }
            }
            if options.label.is_empty() || options.command.is_empty() {
                return Err("run needs --label and a router command after --".into());
            }
            if options.out.as_os_str().is_empty() {
                options.out = PathBuf::from(BENCH_DIR).join(&options.label);
            }
            Ok(Command::Run { corpus, options })
        }
        "compare" => {
            let mut dirs = Vec::new();
            let mut diff = None;
            while let Some(arg) = raw.next() {
                match arg.as_str() {
                    "--diff" => diff = Some(raw.next().ok_or("--diff needs a value")?.into()),
                    _ if arg.starts_with("--") => return Err(format!("unknown option {arg}")),
                    _ => dirs.push(PathBuf::from(arg)),
                }
            }
            let [baseline, candidate]: [PathBuf; 2] = dirs.try_into().map_err(|_| "compare needs two folders")?;
            Ok(Command::Compare { baseline, candidate, diff })
        }
        _ => Err(format!("unknown command {command}")),
    }
}

fn pairs(mut raw: impl Iterator<Item = String>) -> Result<Vec<(String, String)>, String> {
    let mut pairs = Vec::new();
    while let Some(flag) = raw.next() {
        if !flag.starts_with("--") {
            return Err(format!("unexpected argument {flag}"));
        }
        let value = raw.next().ok_or_else(|| format!("{flag} needs a value"))?;
        pairs.push((flag, value));
    }
    Ok(pairs)
}

fn number<T: std::str::FromStr>(flag: &str, value: &str) -> Result<T, String> {
    value.parse().map_err(|_| format!("{flag}: invalid number '{value}'"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse_str(line: &str) -> Result<Command, String> {
        parse(line.split(' ').filter(|arg| !arg.is_empty()).map(str::to_owned))
    }

    #[test]
    fn parses_run_with_router_command() {
        let Ok(Command::Run { corpus, options }) = parse_str("run --label py --runs 2 -- sh -c x --runs") else {
            panic!("expected run");
        };
        assert_eq!(corpus, PathBuf::from("build/translation-bench/corpus.jsonl"));
        assert_eq!((options.label.as_str(), options.runs), ("py", 2));
        assert_eq!(options.command, ["sh", "-c", "x", "--runs"]);
        assert_eq!(options.out, PathBuf::from("build/translation-bench/py"));
    }

    #[test]
    fn parses_corpus_and_compare() {
        let Ok(Command::Corpus { derive, .. }) = parse_str("corpus --derive en") else { panic!() };
        assert_eq!(derive, ["en"]);
        let Ok(Command::Compare { diff, .. }) = parse_str("compare a b --diff d.jsonl") else { panic!() };
        assert_eq!(diff, Some(PathBuf::from("d.jsonl")));
    }

    #[test]
    fn rejects_incomplete_commands() {
        assert!(parse_str("run --label py").is_err());
        assert!(parse_str("run -- python").is_err());
        assert!(parse_str("compare a").is_err());
        assert!(parse_str("corpus --out").is_err());
        assert!(parse_str("nope").is_err());
    }
}
