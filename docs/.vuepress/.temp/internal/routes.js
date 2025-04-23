export const redirects = JSON.parse("{}")

export const routes = Object.fromEntries([
  ["/", { loader: () => import(/* webpackChunkName: "index.html" */"/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/index.html.js"), meta: {"title":"MATSim Episim Documentation"} }],
  ["/get-started.html", { loader: () => import(/* webpackChunkName: "get-started.html" */"/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/get-started.html.js"), meta: {"title":"Get Started"} }],
  ["/documentation/configuration.html", { loader: () => import(/* webpackChunkName: "documentation_configuration.html" */"/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/documentation/configuration.html.js"), meta: {"title":"Configuration"} }],
  ["/documentation/episimsimulation.html", { loader: () => import(/* webpackChunkName: "documentation_episimsimulation.html" */"/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/documentation/episimsimulation.html.js"), meta: {"title":"EpiSim - Simulation"} }],
  ["/documentation/example.html", { loader: () => import(/* webpackChunkName: "documentation_example.html" */"/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/documentation/example.html.js"), meta: {"title":"Example"} }],
  ["/documentation/introduction.html", { loader: () => import(/* webpackChunkName: "documentation_introduction.html" */"/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/documentation/introduction.html.js"), meta: {"title":"EpiSim - Introduction"} }],
  ["/documentation/outputandanalysis.html", { loader: () => import(/* webpackChunkName: "documentation_outputandanalysis.html" */"/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/documentation/outputandanalysis.html.js"), meta: {"title":"EpiSim - Outputs and Analysis"} }],
  ["/documentation/plots.html", { loader: () => import(/* webpackChunkName: "documentation_plots.html" */"/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/documentation/plots.html.js"), meta: {"title":"Plots"} }],
  ["/documentation/quickstart.html", { loader: () => import(/* webpackChunkName: "documentation_quickstart.html" */"/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/documentation/quickstart.html.js"), meta: {"title":"EpiSim - Quickstart"} }],
  ["/documentation/repositorystructure.html", { loader: () => import(/* webpackChunkName: "documentation_repositorystructure.html" */"/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/documentation/repositorystructure.html.js"), meta: {"title":"EpiSim - Repository Structure"} }],
  ["/documentation/run-setup.html", { loader: () => import(/* webpackChunkName: "documentation_run-setup.html" */"/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/documentation/run-setup.html.js"), meta: {"title":"MATSim Episim"} }],
  ["/documentation/simulationsetup.html", { loader: () => import(/* webpackChunkName: "documentation_simulationsetup.html" */"/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/documentation/simulationsetup.html.js"), meta: {"title":"EpiSim - Simulation Setup"} }],
  ["/documentation/website.html", { loader: () => import(/* webpackChunkName: "documentation_website.html" */"/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/documentation/website.html.js"), meta: {"title":"COVID Episim Website"} }],
  ["/404.html", { loader: () => import(/* webpackChunkName: "404.html" */"/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/404.html.js"), meta: {"title":""} }],
]);

if (import.meta.webpackHot) {
  import.meta.webpackHot.accept()
  if (__VUE_HMR_RUNTIME__.updateRoutes) {
    __VUE_HMR_RUNTIME__.updateRoutes(routes)
  }
  if (__VUE_HMR_RUNTIME__.updateRedirects) {
    __VUE_HMR_RUNTIME__.updateRedirects(redirects)
  }
}

if (import.meta.hot) {
  import.meta.hot.accept(({ routes, redirects }) => {
    __VUE_HMR_RUNTIME__.updateRoutes(routes)
    __VUE_HMR_RUNTIME__.updateRedirects(redirects)
  })
}
