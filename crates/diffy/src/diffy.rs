
#[derive(uniffi::Enum, Copy, Clone, Debug)]
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
                // Flush a trailing delete if one was pending.
                if let Some((pos, src_lines)) = pending_rem.take() {
                    out.push(Delta {
                        kind: DeltaKind::Delete,
                        target_position: pos,
                        source_lines: src_lines,
                        target_lines: vec![],
                    });
                }
                // Advance current line index by the number of lines in this unchanged block.
                if !block.is_empty() {
                    curr_line_idx = curr_line_idx.saturating_add(block.lines().count() as u32);
                }
            }
            Difference::Rem(block) => {
                let src_lines: Vec<String> = if block.is_empty() {
                    vec![]
                } else {
                    block.lines().map(|s| s.to_string()).collect()
                };
                // Remember the delete at the current position; may be paired with a following Add.
                pending_rem = Some((curr_line_idx, src_lines));
            }
            Difference::Add(block) => {
                let tgt_lines: Vec<String> = if block.is_empty() {
                    vec![]
                } else {
                    block.lines().map(|s| s.to_string()).collect()
                };

                if let Some((pos, src_lines)) = pending_rem.take() {
                    // Change (replace) at position `pos`.
                    out.push(Delta {
                        kind: DeltaKind::Change,
                        target_position: pos,
                        source_lines: src_lines,
                        target_lines: tgt_lines.clone(),
                    });
                } else {
                    // Pure insert at current index.
                    out.push(Delta {
                        kind: DeltaKind::Insert,
                        target_position: curr_line_idx,
                        source_lines: vec![],
                        target_lines: tgt_lines.clone(),
                    });
                }

                curr_line_idx = curr_line_idx.saturating_add(tgt_lines.len() as u32);
            }
        }
    }

    // If the last change was a delete without a following add, emit it now.
    if let Some((pos, source_lines)) = pending_rem.take() {
        out.push(Delta {
            kind: DeltaKind::Delete,
            target_position: pos,
            source_lines,
            target_lines: vec![],
        });
    }

    out
}
