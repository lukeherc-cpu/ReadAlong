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
}

const book = (id: string, title: string, author: string, chapter: string, theme: Theme, left: string, total: string, lines: string[], more: string[] = []): Book => ({
  id, title, author, chapter, theme, left, total, lines, more, cover: media(`cover-${id}.webp`),
})

export const BOOKS = {
  pride: book('pride', 'Pride and Prejudice', 'Jane Austen', 'Chapter 1', { tint: '#4A4044', page: '#2B2426', card: '#5A4E52', cream: '#F2E3C9' }, '11h 34m left in book', '12:40',
    ['It is a truth universally acknowledged,', 'that a single man in possession of a good fortune,', 'must be in want of a wife.'],
    [
      'However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well fixed in the minds of the surrounding families, that he is considered as the rightful property of some one or other of their daughters.',
      '“My dear Mr. Bennet,” said his lady to him one day, “have you heard that Netherfield Park is let at last?”',
      'Mr. Bennet replied that he had not.',
    ]),
  potter: book('potter', 'Harry Potter', 'J.K. Rowling', 'Chapter 1 – The Boy Who Lived', { tint: '#26485A', page: '#18303C', card: '#335E72', cream: '#DDEFF7' }, '8h 40m left in book', '31:47',
    ['Mr. and Mrs. Dursley, of number four, Privet Drive,', 'were proud to say that they were perfectly normal,', 'thank you very much.'],
    ['They were the last people you’d expect to be involved in anything strange or mysterious, because they just didn’t hold with such nonsense.']),
  dune: book('dune', 'Dune', 'Frank Herbert', 'Chapter 1', { tint: '#6E4521', page: '#432A15', card: '#83562E', cream: '#F7DFBA' }, '20h 44m left in book', '27:56',
    ['I must not fear.', 'Fear is the mind-killer.', 'Fear is the little-death that brings total obliteration.']),
  hobbit: book('hobbit', 'The Hobbit', 'J.R.R. Tolkien', 'Chapter 1 – An Unexpected Party', { tint: '#4A5058', page: '#2A2E33', card: '#5A626B', cream: '#E9EDF2' }, '10h 24m left in book', '55:46',
    ['In a hole in the ground there lived a hobbit.', 'Not a nasty, dirty, wet hole, filled with the ends of worms and an oozy smell,', 'nor yet a dry, bare, sandy hole with nothing in it to sit down on or to eat:', 'it was a hobbit-hole, and that means comfort.']),
  orwell: book('1984', '1984', 'George Orwell', 'Part 1 – Chapter 1', { tint: '#5E1414', page: '#1E1212', card: '#5A2020', cream: '#F2D7D2' }, '12h 11m left in book', '44:48',
    ['It was a bright cold day in April,', 'and the clocks were striking thirteen.', 'Winston Smith slipped quickly through the glass doors of Victory Mansions.']),
}
