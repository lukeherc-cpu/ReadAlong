import { media } from './links'

/** A book's colors, taken from its cover the way the app does: the player's top tint, the reader page, the text card and the pale accent. */
export type Theme = { tint: string; page: string; card: string; cream: string }
export type Book = {
  id: string
  title: string
  author: string
  chapter: string
  cover: string
  theme: Theme
  /** The sentences (or phrases) the narrator reads, in order. */
  lines: string[]
  /** Text further down the page. */
  more: string[]
  left: string
  total: string
  /** A short narration clip: when each of the first lines begins, in seconds, and how long the clip is. */
  clip?: { src: string; cues: number[]; length: number }
}

const book = (id: string, title: string, author: string, chapter: string, theme: Theme, left: string, total: string, lines: string[], more: string[] = [], clip?: [number[], number]): Book => ({
  id, title, author, chapter, theme, left, total, lines, more, cover: media(`cover-${id}.webp`),
  clip: clip && { src: media(`${id}.mp3`), cues: clip[0], length: clip[1] },
})

export const BOOKS = {
  pride: book('pride', 'Pride and Prejudice', 'Jane Austen', 'Chapter 1', { tint: '#4A4044', page: '#2B2426', card: '#5A4E52', cream: '#F2E3C9' }, '11h 34m left in book', '12:40',
    ['It is a truth universally acknowledged,', 'that a single man in possession of a good fortune,', 'must be in want of a wife.'],
    [
      'However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well fixed in the minds of the surrounding families, that he is considered as the rightful property of some one or other of their daughters.',
      '“My dear Mr. Bennet,” said his lady to him one day, “have you heard that Netherfield Park is let at last?”',
      'Mr. Bennet replied that he had not.',
    ], [[0, 2.55, 5.0], 6.7]),
  potter: book('potter', 'Harry Potter', 'J.K. Rowling', 'Chapter 1 – The Boy Who Lived', { tint: '#26485A', page: '#18303C', card: '#335E72', cream: '#DDEFF7' }, '8h 40m left in book', '31:47',
    ['Mr. and Mrs. Dursley, of number four, Privet Drive,', 'were proud to say that they were perfectly normal,', 'thank you very much.'],
    ['They were the last people you’d expect to be involved in anything strange or mysterious, because they just didn’t hold with such nonsense.'], [[0, 4.0, 6.7], 8.3]),
  dune: book('dune', 'Dune', 'Frank Herbert', 'Chapter 1', { tint: '#6E4521', page: '#432A15', card: '#83562E', cream: '#F7DFBA' }, '20h 44m left in book', '27:56',
    ['I must not fear.', 'Fear is the mind-killer.', 'Fear is the little-death that brings total obliteration.'], [], [[0, 1.8], 3.85]),
  hobbit: book('hobbit', 'The Hobbit', 'J.R.R. Tolkien', 'Chapter 1 – An Unexpected Party', { tint: '#4A5058', page: '#2A2E33', card: '#5A626B', cream: '#E9EDF2' }, '10h 24m left in book', '55:46',
    ['In a hole in the ground there lived a hobbit.', 'Not a nasty, dirty, wet hole,', 'filled with the ends of worms and an oozy smell,', 'nor yet a dry, bare, sandy hole with nothing in it to sit down on or to eat:', 'it was a hobbit-hole, and that means comfort.'], [], [[0, 5.4], 8.1]),
  habits: book('habits', 'Atomic Habits', 'James Clear', 'Introduction', { tint: '#5A4630', page: '#3A2C1E', card: '#6B5638', cream: '#F3E6CC' }, '5h 12m left in book', '31:02',
    ['You do not rise to the level of your goals.', 'You fall to the level of your systems.'],
    ['Focusing on the overall system, rather than a single goal, is one of the core themes of this book.'], [[0, 2.9], 5.0]),
}
