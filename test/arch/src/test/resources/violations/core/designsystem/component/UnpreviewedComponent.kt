package fixtures

// VIOLATION FIXTURE - never compiled. Rule 7 must reject this: a public @Composable
// in the design system with no @ThemePreviews.
@Composable
fun UnpreviewedComponent(label: String) {
    Text(label)
}
