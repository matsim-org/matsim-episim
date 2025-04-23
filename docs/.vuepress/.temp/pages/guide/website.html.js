import comp from "/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/guide/website.html.vue"
const data = JSON.parse("{\"path\":\"/guide/website.html\",\"title\":\"COVID Episim Website\",\"lang\":\"en-US\",\"frontmatter\":{},\"git\":{},\"filePathRelative\":\"guide/website.md\"}")
export { comp, data }

if (import.meta.webpackHot) {
  import.meta.webpackHot.accept()
  if (__VUE_HMR_RUNTIME__.updatePageData) {
    __VUE_HMR_RUNTIME__.updatePageData(data)
  }
}

if (import.meta.hot) {
  import.meta.hot.accept(({ data }) => {
    __VUE_HMR_RUNTIME__.updatePageData(data)
  })
}
