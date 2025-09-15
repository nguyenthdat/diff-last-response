uniffi::setup_scaffolding!();

#[derive(uniffi::Enum, Copy, Clone, Debug, PartialEq, Eq)]
pub enum DeltaKind { Insert, Delete, Change }

#[derive(uniffi::Record, Clone, Debug)]
pub struct Delta {
    pub kind: DeltaKind,
    /// Zero-based line index in the *current* text where this delta applies.
    pub target_position: u32,
    /// Lines from the previous version (empty for pure inserts).
    pub source_lines: Vec<String>,
    /// Lines from the current version (empty for pure deletes).
    pub target_lines: Vec<String>,
}

#[derive(uniffi::Record, Clone, Debug)]
pub struct LineBlock {
    pub kind: DeltaKind,
    pub start_line: u32,
    pub line_count: u32,
}

#[derive(uniffi::Record, Clone, Debug)]
pub struct InlineSpan {
    /// Line index in the *current* text.
    pub line: u32,
    /// Start column in UTF-16 code units.
    pub start_col_utf16: u32,
    /// End column (exclusive) in UTF-16 code units.
    pub end_col_utf16: u32,
}

#[derive(uniffi::Record, Clone, Debug)]
pub struct Decorations {
    pub line_blocks: Vec<LineBlock>,
    pub inline_spans: Vec<InlineSpan>,
}

/// Compute grouped line-level deltas. We coalesce a Rem+Add pair into a single CHANGE.
#[uniffi::export(name = "computeDeltas")]
pub fn compute_deltas(previous: String, current: String) -> Vec<Delta> {
    use difference::{Changeset, Difference};

    let mut out: Vec<Delta> = Vec::new();
    let mut curr_line_idx: u32 = 0; // running index into *current* text
    let mut pending_rem: Option<(u32, Vec<String>)> = None;

    let cs = Changeset::new(&previous, &current, "\n");
    for diff in cs.diffs {
        match diff {
            Difference::Same(block) => {
                if let Some((pos, src_lines)) = pending_rem.take() {
                    out.push(Delta { kind: DeltaKind::Delete, target_position: pos, source_lines: src_lines, target_lines: vec![] });
                }
                if !block.is_empty() {
                    curr_line_idx = curr_line_idx.saturating_add(block.lines().count() as u32);
                }
            }
            Difference::Rem(block) => {
                let src_lines: Vec<String> = if block.is_empty() { vec![] } else { block.lines().map(|s| s.to_string()).collect() };
                pending_rem = Some((curr_line_idx, src_lines));
            }
            Difference::Add(block) => {
                let tgt_lines: Vec<String> = if block.is_empty() { vec![] } else { block.lines().map(|s| s.to_string()).collect() };
                if let Some((pos, src_lines)) = pending_rem.take() {
                    out.push(Delta { kind: DeltaKind::Change, target_position: pos, source_lines: src_lines, target_lines: tgt_lines.clone() });
                } else {
                    out.push(Delta { kind: DeltaKind::Insert, target_position: curr_line_idx, source_lines: vec![], target_lines: tgt_lines.clone() });
                }
                curr_line_idx = curr_line_idx.saturating_add(tgt_lines.len() as u32);
            }
        }
    }
    if let Some((pos, src_lines)) = pending_rem.take() {
        out.push(Delta { kind: DeltaKind::Delete, target_position: pos, source_lines: src_lines, target_lines: vec![] });
    }
    out
}

/// Compute line blocks and UTF-16-aware inline spans for changed lines.
#[uniffi::export(name = "computeDecorations")]
pub fn compute_decorations(previous: String, current: String) -> Decorations {
    use difference::{Changeset, Difference};

    let mut line_blocks: Vec<LineBlock> = Vec::new();
    let mut inline_spans: Vec<InlineSpan> = Vec::new();

    let mut curr_line_idx: u32 = 0;
    let mut pending_rem: Option<(u32, Vec<String>)> = None;

    let cs = Changeset::new(&previous, &current, "\n");
    for diff in cs.diffs {
        match diff {
            Difference::Same(block) => {
                if let Some((pos, src_lines)) = pending_rem.take() {
                    // Pure delete block
                    line_blocks.push(LineBlock { kind: DeltaKind::Delete, start_line: pos, line_count: src_lines.len() as u32 });
                }
                if !block.is_empty() {
                    curr_line_idx = curr_line_idx.saturating_add(block.lines().count() as u32);
                }
            }
            Difference::Rem(block) => {
                let src_lines: Vec<String> = if block.is_empty() { vec![] } else { block.lines().map(|s| s.to_string()).collect() };
                pending_rem = Some((curr_line_idx, src_lines));
            }
            Difference::Add(block) => {
                let tgt_lines: Vec<String> = if block.is_empty() { vec![] } else { block.lines().map(|s| s.to_string()).collect() };
                if let Some((pos, src_lines)) = pending_rem.take() {
                    // CHANGE: line block
                    line_blocks.push(LineBlock { kind: DeltaKind::Change, start_line: pos, line_count: tgt_lines.len() as u32 });
                    // Inline spans for changed lines (pairwise up to min length)
                    let pairs = src_lines.iter().zip(tgt_lines.iter());
                    for (i, (src, tgt)) in pairs.enumerate() {
                        if let Some((start_u16, end_u16)) = changed_span_utf16(src, tgt) {
                            inline_spans.push(InlineSpan { line: pos + i as u32, start_col_utf16: start_u16, end_col_utf16: end_u16 });
                        }
                    }
                } else {
                    // Pure insert
                    line_blocks.push(LineBlock { kind: DeltaKind::Insert, start_line: curr_line_idx, line_count: tgt_lines.len() as u32 });
                }
                curr_line_idx = curr_line_idx.saturating_add(tgt_lines.len() as u32);
            }
        }
    }

    if let Some((pos, src_lines)) = pending_rem.take() {
        line_blocks.push(LineBlock { kind: DeltaKind::Delete, start_line: pos, line_count: src_lines.len() as u32 });
    }

    Decorations { line_blocks, inline_spans }
}

/// Return a single changed span between `old_line` and `new_line` in UTF-16 columns, if any.
/// We compute common prefix/suffix in UTF-16 code units and mark the middle as changed.
fn changed_span_utf16(old_line: &str, new_line: &str) -> Option<(u32, u32)> {
    let a: Vec<u16> = old_line.encode_utf16().collect();
    let b: Vec<u16> = new_line.encode_utf16().collect();

    if a == b { return None; }

    let mut i = 0usize;
    let max_pref = a.len().min(b.len());
    while i < max_pref && a[i] == b[i] { i += 1; }

    let mut j = 0usize;
    let max_suf = a.len().saturating_sub(i).min(b.len().saturating_sub(i));
    while j < max_suf && a[a.len()-1-j] == b[b.len()-1-j] { j += 1; }

    let start = i as u32;
    let end = (b.len() - j) as u32;
    if start < end { Some((start, end)) } else { None }
}
