/* Stands in for a vendored upstream source file.
 *
 * The recorded hash in ../../pjsip/patches/vendored-tree.sha256 is deliberately NOT the
 * hash of this tree: that is the whole fixture. It is what an unrecorded edit to a
 * vendored file looks like from the outside - the bytes moved and nothing in
 * pjsip/patches/ says why.
 */
int upstream_function(void) { return 42; }
