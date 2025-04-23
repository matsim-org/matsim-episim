import comp from "/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/index.html.vue"
const data = JSON.parse("{\"path\":\"/\",\"title\":\"MATSim Episim Documentation\",\"lang\":\"en-US\",\"frontmatter\":{\"home\":true,\"title\":\"MATSim Episim Documentation\",\"heroImage\":\"https://www.ivt.ethz.ch/forschung/matsim/_jcr_content/par/fullwidthimage/image.imageformat.1286.2026935405.png\",\"actions\":[{\"text\":\"Quickstart\",\"link\":\"/documentation/quickstart.html\",\"type\":\"primary\"},{\"text\":\"Introduction\",\"link\":\"/documentation/introduction.html\",\"type\":\"secondary\"}],\"features\":[{\"title\":\"Epidemic Simulation Based on MATSim\",\"details\":\"MATSim Episim is an agent-based epidemiological simulation framework designed to evaluate non-pharmaceutical interventions such as mobility restrictions, mask mandates, and vaccination campaigns.\"},{\"title\":\"Modular and Configurable\",\"details\":\"The simulation setup is fully configurable using YAML files and can be adapted to different cities, start dates, and intervention policies.\"},{\"title\":\"Scientific Foundations\",\"details\":\"Based on peer-reviewed research and official data sources, including mobility patterns, infection models, vaccination strategies, and antibody waning.\"},{\"title\":\"Open Source Visualization\",\"details\":\"Results can be visualized using the Covid-Sim frontend. The output supports R-value plotting, incidence heatmaps, hospitalization rates, and more.\"},{\"title\":\"Multiple Scenarios\",\"details\":\"Users can define multiple runs for different cities and timeframes, enabling comparative policy analysis and robust forecasting.\"},{\"title\":\"Community Driven\",\"details\":\"Built by the Technische Universität Berlin (TUB) and VSP. Contributions and forks are welcome to support new diseases or regions.\"}],\"footer\":\"MIT Licensed | © 2024 Technische Universität Berlin – VSP Group\"},\"git\":{},\"filePathRelative\":\"README.md\"}")
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
