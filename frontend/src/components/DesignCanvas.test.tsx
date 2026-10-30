import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import DesignCanvas from './DesignCanvas'

describe('DesignCanvas', () => {
  it('submitsThroughTheGivenFunction', async () => {
    const submit = vi.fn().mockResolvedValue({ feedback: 'Nice', differences: '', points: [] })
    render(
      <DesignCanvas
        meta="Book · Cache · mini design"
        title="Add a cache"
        requirements={['Reads are fast']}
        submit={submit}
        onDone={() => {}}
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: 'Cache' }))
    await userEvent.click(screen.getByRole('button', { name: 'Submit for review' }))
    expect(submit).toHaveBeenCalledWith(expect.objectContaining({ nodes: [expect.objectContaining({ label: 'Cache' })] }))
    expect(await screen.findByText('Nice')).toBeInTheDocument()
  })
})
