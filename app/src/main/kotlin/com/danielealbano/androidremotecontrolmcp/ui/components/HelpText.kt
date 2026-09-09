package com.danielealbano.androidremotecontrolmcp.ui.components

/**
 * The title and body of a setting's explanation, as shown by [HelpHint].
 *
 * A type rather than two parameters: the pair travels together through several layers of settings
 * primitives, and splitting it invited call sites that passed a title with someone else's body.
 */
data class HelpText(
    val title: String,
    val body: String,
)
