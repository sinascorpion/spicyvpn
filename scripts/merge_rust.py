"""Three-way Rust item merge; incompatible edits still require explicit review."""
import hashlib
import re
from tree_sitter import Language, Parser
import tree_sitter_rust

PARSER = Parser(Language(tree_sitter_rust.language()))


def parse(source):
    tree = PARSER.parse(source)
    if tree.root_node.has_error:
        # The pinned grammar mistakes a borrow of an identifier named `raw`
        # for a raw-pointer expression. Keep byte offsets and source intact.
        view = re.sub(rb"&raw\b(?!\s+(?:const|mut)\b)", b"&r#w", source)
        if view != source:
            candidate = PARSER.parse(view)
            if not candidate.root_node.has_error:
                return candidate
    return tree

# Reviewed upstream 2.1 overlap: preserve Android data-plane verification and
# detailed refusal reporting. Any later edit requires a new explicit review.
REVIEWED_LOCAL = {
    "struct_item:LastConnection": (
        "9d7461e17572df8ac1260df9e970eab4ff939a298394f97055fae10830362295",
        "bb26515398f9c2370e7f6480635638b96d23f0ff10115a3744ecd23ba650adbc",
    ),
    "function_item:save": (
        "bc6488c8ff82a99164c340609b5117d49244453f8255522c14254e5208eca5ce",
        "cc561853594942ff96960fd9e3673724a4d586a8d18b3ef65608d6f8db86e33d",
    ),
    "mod_item:tests": (
        "b8001f4bae37003007c26f572f09f13e35959f9db16454f5409f015466709b7f",
        "4b7a04db40d7b98c24f4745bda15b573136f1be6433534af5322341b1bea8119",
    ),
    "function_item:run_wireguard": (
        "1e8b6534ed5b59918a1fdad33dab6694ddb31335d3fd7a7e1a423ee6c8c57d2e",
        "af25de1ea1ac895801265a5a7a5bfca7da7309a6fbe424a424c519251982c6f0",
    ),
    "function_item:run_mim": (
        "dafd941a8fc9a91ae11dda2df996f3cb7c947e9a87af0a5869a35aae0095a7c4",
        "78930ec651464a43d4f08e25ae58c3e1db197e65c2d19e597dc34f66f8b6507a",
    ),
    "function_item:run_masque": (
        "95b6f03805c2f395e3e8f57b3621692fd03b07e67201ebb75e0a59606943a31b",
        "5278e598b06be3c240ece94b6f6bd3d542c4574116caece1570842d58a445ba2",
    ),
    "function_item:verify_h2": (
        "4fd469fb8cf73e5705698cc1b042ca21055ded9080df8759fb94df8780c52823",
        "62f8f28acab77555a5c18ec452b23176103000abe8326b05db7603131ea4bb3b",
    ),
}


def syntax_tokens(source):
    """Compare Rust syntax without treating documentation edits as code conflicts."""
    if source is None:
        return None
    root = parse(source).root_node
    if root.has_error:
        return source
    tokens = []
    def visit(node):
        if node.type in ("line_comment", "block_comment"):
            return
        if not node.children:
            tokens.append((node.type, source[node.start_byte:node.end_byte]))
        else:
            for child in node.children:
                visit(child)
    visit(root)
    return tokens


def items(source):
    root = parse(source).root_node
    if root.has_error:
        raise RuntimeError("Rust source does not parse")
    result, order, cursor = {}, [], 0
    for node in root.named_children:
        if node.type in ("line_comment", "block_comment", "attribute_item", "inner_attribute_item"):
            continue
        name = node.child_by_field_name("name")
        if name is None and node.type == "impl_item":
            name = node.child_by_field_name("type")
        identifier = name.text.decode() if name else re.sub(rb"\s+", b"", node.text).removeprefix(b"pub").decode()
        if node.type == "impl_item":
            trait = node.child_by_field_name("trait")
            identifier += ":" + (trait.text.decode() if trait else "inherent")
        key = node.type + ":" + identifier
        if key in result:
            raise RuntimeError("Ambiguous duplicate Rust item: " + key)
        result[key] = source[cursor:node.end_byte].strip() + b"\n"
        order.append(key)
        cursor = node.end_byte
    if source[cursor:].strip():
        result["trailer"] = source[cursor:]
        order.append("trailer")
    return result, order


def merge(local, base, remote, merge_file, scratch, diagnostics):
    maps = [items(data) for data in (local, base, remote)]
    a, b, c = (entry[0] for entry in maps)
    order = list(dict.fromkeys(maps[2][1] + maps[0][1] + maps[1][1]))
    merged, conflicts = [], []
    for key in order:
        values = tuple(mapping.get(key) for mapping in (a, b, c))
        try:
            reviewed = REVIEWED_LOCAL.get(key)
            fingerprints = tuple(hashlib.sha256(value).hexdigest() if value is not None else None
                                 for value in (values[0], values[2]))
            if reviewed is not None and reviewed == fingerprints:
                content = values[0]
            elif values[0] is not None and syntax_tokens(values[0]) == syntax_tokens(values[2]):
                content = values[0]
            else:
                content = merge_file(*values, scratch)
        except RuntimeError:
            token = hashlib.sha256(key.encode()).hexdigest()[:12]
            destination = diagnostics / token
            destination.mkdir(parents=True, exist_ok=True)
            (destination / "item.txt").write_text(key)
            for suffix, content in zip(("local", "base", "upstream"), values):
                (destination / suffix).write_bytes(content or b"")
            conflicts.append(key)
            continue
        if content is not None:
            merged.append(content)
    if conflicts:
        raise RuntimeError("Rust items requiring review: " + ", ".join(conflicts))
    output = b"\n".join(merged)
    if parse(output).root_node.has_error:
        raise RuntimeError("Merged Rust source does not parse")
    return output
