import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import LessonText from './LessonText'

describe('LessonText', () => {
  it('rendersParagraphsCodeBlocksAndInlineCode', () => {
    render(<LessonText text={'First para with `List<?>` inside.\n\nSecond para.\n\n```java\nList<? extends Number> xs;\n```'} />)
    expect(screen.getByText('List<?>').tagName).toBe('CODE')
    expect(screen.getByText('Second para.').tagName).toBe('P')
    expect(screen.getByText('List<? extends Number> xs;').closest('pre')).toHaveClass('lesson-code')
  })
})
