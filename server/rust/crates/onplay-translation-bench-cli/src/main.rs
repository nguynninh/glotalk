mod args;

use std::io::Write;
use std::process::ExitCode;

use onplay_translation_bench::stats::Latency;
use onplay_translation_bench::{corpus, Comparison, Event, Summary};

use args::{Command, USAGE};

fn main() -> ExitCode {
    let command = match args::parse(std::env::args().skip(1)) {
        Ok(command) => command,
        Err(error) => {
            eprintln!("Error: {error}\n\n{USAGE}");
            return ExitCode::from(2);
        }
    };
    match execute(command) {
        Ok(code) => code,
        Err(error) => {
            eprintln!("Error: {error}");
            ExitCode::FAILURE
        }
    }
}

fn execute(command: Command) -> onplay_translation_bench::Result<ExitCode> {
    match command {
        Command::Corpus { sessions, derive, out } => {
            let items = corpus::extract(&sessions, &derive)?;
            corpus::write(&out, &items)?;
            println!("{} items -> {}", items.len(), out.display());
        }
        Command::Run { corpus: path, options } => {
            let items = corpus::read(&path)?;
            let summary = onplay_translation_bench::run(&items, &options, &progress)?;
            eprintln!();
            print_summary(&summary);
            println!("results: {}", options.out.display());
        }
        Command::Compare { baseline, candidate, diff } => {
            let comparison = Comparison::load(&baseline, &candidate)?;
            print_comparison(&comparison);
            if let Some(path) = diff {
                comparison.write_differences(&path)?;
                println!("differences: {}", path.display());
            }
            if !comparison.differences.is_empty() {
                return Ok(ExitCode::FAILURE);
            }
        }
    }
    Ok(ExitCode::SUCCESS)
}

fn progress(event: Event) {
    match event {
        Event::Starting { run } => eprint!("run {run}: starting router..."),
        Event::Ready { startup, .. } => eprintln!(" ready in {:.1}s", startup.as_secs_f64()),
        Event::Translated { run, done, total } => {
            eprint!("\rrun {run}: {done}/{total}");
            let _ = std::io::stderr().flush();
        }
        Event::Stopped { .. } => eprintln!(),
    }
}

fn print_summary(summary: &Summary) {
    println!("{}: {} items x {} runs", summary.label, summary.items, summary.runs.len());
    println!("run  startup_s  idle_MB  peak_MB  p50_ms  p95_ms  max_ms  failed  fallback  cached");
    for run in &summary.runs {
        let latency = run.latency.as_ref();
        println!(
            "{:>3}  {:>9.1}  {:>7.1}  {:>7.1}  {:>6}  {:>6}  {:>6}  {:>6}  {:>8}  {:>6}",
            run.run,
            run.startup_ms / 1000.0,
            run.idle_rss_mb,
            run.peak_rss_mb,
            ms(latency.map(|l| l.p50_ms)),
            ms(latency.map(|l| l.p95_ms)),
            ms(latency.map(|l| l.max_ms)),
            run.failures,
            run.fallbacks,
            run.cached,
        );
    }
    print_latency("all", summary.latency.as_ref());
    for (model, latency) in &summary.latency_by_model {
        print_latency(model, Some(latency));
    }
    println!("unstable across runs: {}", summary.unstable.len());
}

fn print_latency(name: &str, latency: Option<&Latency>) {
    if let Some(l) = latency {
        println!(
            "{name:>12}: n={} mean={:.1} p50={:.1} p95={:.1} p99={:.1} max={:.1} ms",
            l.count, l.mean_ms, l.p50_ms, l.p95_ms, l.p99_ms, l.max_ms
        );
    }
}

fn print_comparison(comparison: &Comparison) {
    let (a, b) = (&comparison.baseline, &comparison.candidate);
    println!("identical outputs: {}/{}", comparison.identical(), comparison.items);
    println!("unstable across runs: {} {} / {} {}", a.label, a.unstable.len(), b.label, b.unstable.len());
    println!("{:<18}{:>12}{:>12}{:>9}", "", a.label, b.label, "ratio");
    let mean = |s: &Summary, f: fn(&onplay_translation_bench::report::RunSummary) -> f64| {
        s.runs.iter().map(f).sum::<f64>() / s.runs.len().max(1) as f64
    };
    row("startup_s (mean)", mean(a, |r| r.startup_ms) / 1000.0, mean(b, |r| r.startup_ms) / 1000.0);
    row("idle_MB (mean)", mean(a, |r| r.idle_rss_mb), mean(b, |r| r.idle_rss_mb));
    row("peak_MB (max)", max(a, |r| r.peak_rss_mb), max(b, |r| r.peak_rss_mb));
    if let (Some(x), Some(y)) = (a.latency, b.latency) {
        row("p50_ms", x.p50_ms, y.p50_ms);
        row("p95_ms", x.p95_ms, y.p95_ms);
        row("p99_ms", x.p99_ms, y.p99_ms);
        row("mean_ms", x.mean_ms, y.mean_ms);
    }
    row("failed (run 1)", failures(a), failures(b));
}

fn max(summary: &Summary, f: fn(&onplay_translation_bench::report::RunSummary) -> f64) -> f64 {
    summary.runs.iter().map(f).fold(0.0, f64::max)
}

fn failures(summary: &Summary) -> f64 {
    summary.runs.first().map_or(0.0, |run| run.failures as f64)
}

fn row(name: &str, a: f64, b: f64) {
    let ratio = if a > 0.0 { format!("{:.2}x", b / a) } else { "-".into() };
    println!("{name:<18}{a:>12.1}{b:>12.1}{ratio:>9}");
}

fn ms(value: Option<f64>) -> String {
    value.map_or("-".into(), |v| format!("{v:.0}"))
}
