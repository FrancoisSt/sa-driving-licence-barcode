//! The adaptive Huffman coder of spec/wi-codec.md section 4: FGK with the sibling property, over `N` symbols plus
//! a large-value leaf (`N`) and an escape leaf (`N + 1`).
//!
//! Every node index is produced by the tree's own bookkeeping, never read from the input; the only symbols that come
//! from the input are escaped values of at most 10 bits, below both alphabets. Accesses are still checked, so a logic
//! error could only give a wrong image, never a panic.

use crate::bits::BitReader;
use crate::WiError;

const NONE: usize = usize::MAX;

#[derive(Clone, Copy)]
struct Node {
    parent: usize,
    leaf: bool,
    /// A leaf's symbol, or an internal node's first child (the second child is `payload + 1`).
    payload: usize,
    weight: u32,
}

/// A node outside the table reads as a weightless leaf, which ends any walk.
const OUTSIDE: Node = Node { parent: NONE, leaf: true, payload: 0, weight: 0 };

pub(crate) struct Model {
    n: usize,
    nodes: Vec<Node>,
    node_of: Vec<usize>,
    next: usize,
}

impl Model {
    /// Section 4.2: the root with its two special leaves, then `presets` symbols added and counted once.
    pub(crate) fn new(n: usize, presets: usize) -> Model {
        let mut nodes = vec![OUTSIDE; 2 * n + 3];
        nodes[0] = Node { parent: NONE, leaf: false, payload: 1, weight: 2 };
        nodes[1] = Node { parent: 0, leaf: true, payload: n, weight: 1 };
        nodes[2] = Node { parent: 0, leaf: true, payload: n + 1, weight: 1 };
        let mut node_of = vec![NONE; n + 2];
        node_of[n] = 1;
        node_of[n + 1] = 2;
        let mut model = Model { n, nodes, node_of, next: 3 };
        for s in 0..presets.min(n) {
            model.add(s);
            model.update(s);
        }
        model
    }

    /// The map model: 256 symbols, 0 to 63 preset.
    pub(crate) fn for_map() -> Model {
        Model::new(256, 64)
    }

    /// A coefficient model: 2048 symbols, 0 to 2 preset.
    pub(crate) fn for_coefficients() -> Model {
        Model::new(2048, 3)
    }

    fn node(&self, i: usize) -> Node {
        self.nodes.get(i).copied().unwrap_or(OUTSIDE)
    }

    fn set_node_of(&mut self, symbol: usize, node: usize) {
        if let Some(slot) = self.node_of.get_mut(symbol) {
            *slot = node;
        }
    }

    fn set_parent(&mut self, i: usize, parent: usize) {
        if let Some(node) = self.nodes.get_mut(i) {
            node.parent = parent;
        }
    }

    /// Section 4.3: node `next − 1` (a leaf) becomes an internal node over its old content and a new leaf for `s`.
    fn add(&mut self, s: usize) {
        if s >= self.n || self.node_of.get(s).copied() != Some(NONE) {
            return;
        }
        let d = self.next;
        if d + 1 >= self.nodes.len() {
            return; // cannot happen: at most N symbols are added, two nodes each, into 2N + 3 nodes
        }
        let last = self.node(d - 1);
        self.nodes[d] = Node { parent: d - 1, ..last };
        self.set_node_of(last.payload, d);
        self.nodes[d - 1].leaf = false;
        self.nodes[d - 1].payload = d;
        self.nodes[d + 1] = Node { parent: d - 1, leaf: true, payload: s, weight: 0 };
        self.node_of[s] = d + 1;
        self.next = d + 2;
    }

    /// Section 4.4: count one more `s`, moving each node on its path to the front of its weight class first.
    fn update(&mut self, s: usize) {
        let mut i = self.node_of.get(s).copied().unwrap_or(NONE);
        while let Some(node) = self.nodes.get_mut(i) {
            node.weight = node.weight.saturating_add(1);
            let weight = node.weight;
            let mut j = i;
            while j > 0 && self.node(j - 1).weight < weight {
                j -= 1;
            }
            if j != i {
                self.swap(i, j);
                i = j;
            }
            i = self.node(i).parent;
        }
    }

    /// Section 4.5: exchange two nodes' contents; each keeps its own parent.
    fn swap(&mut self, i: usize, j: usize) {
        for (x, y) in [(i, j), (j, i)] {
            let node = self.node(x);
            if node.leaf {
                self.set_node_of(node.payload, y);
            } else {
                self.set_parent(node.payload, y);
                self.set_parent(node.payload + 1, y);
            }
        }
        let (a, b) = (self.node(i), self.node(j));
        if let (Some(_), Some(_)) = (self.nodes.get(i), self.nodes.get(j)) {
            self.nodes[i] = Node { parent: a.parent, ..b };
            self.nodes[j] = Node { parent: b.parent, ..a };
        }
    }

    /// Section 4.6: one symbol, 0 to N (N is the large-value leaf). An escape reads `escape_bits` literal bits.
    pub(crate) fn decode(&mut self, reader: &mut BitReader<'_>, escape_bits: u32) -> Result<usize, WiError> {
        let mut i = 0;
        loop {
            let node = self.node(i);
            if node.leaf {
                break;
            }
            i = node.payload + reader.bit()? as usize;
        }
        let mut s = self.node(i).payload;
        if s == self.n + 1 {
            s = reader.bits(escape_bits)? as usize; // below N: at most 10 bits here
            self.add(s);
        }
        self.update(s);
        Ok(s)
    }

    /// Section 4.7: a run length. 255 and 256 each announce an extension, `hi × 256 + lo` with wrap-around; the
    /// specification's recursion (hi first, then lo) is unrolled into a count of extensions and a loop.
    pub(crate) fn run(&mut self, reader: &mut BitReader<'_>) -> Result<i32, WiError> {
        let mut extensions = 0u32;
        let mut s = self.decode(reader, 8)?;
        while s >= 255 {
            extensions += 1;
            s = self.decode(reader, 8)?;
        }
        let mut value = s as i32;
        for _ in 0..extensions {
            let lo = self.decode(reader, 8)? as i32;
            value = value.wrapping_mul(256).wrapping_add(lo);
        }
        Ok(value)
    }

    /// The bits from the root to `symbol`'s leaf, as a string of '0' and '1' (the self-test of section 4.8).
    #[cfg(test)]
    pub(crate) fn code(&self, symbol: usize) -> String {
        let mut bits = Vec::new();
        let mut i = self.node_of[symbol];
        while self.node(i).parent != NONE {
            let parent = self.node(i).parent;
            bits.push(if i == self.node(parent).payload { '0' } else { '1' });
            i = parent;
        }
        bits.iter().rev().collect()
    }

    #[cfg(test)]
    pub(crate) fn root_weight_and_size(&self) -> (u32, usize) {
        (self.node(0).weight, self.next)
    }
}
