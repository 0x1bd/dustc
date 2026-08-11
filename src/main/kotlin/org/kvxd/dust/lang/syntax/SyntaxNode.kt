package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal sealed interface SyntaxNode { val location: Token }
